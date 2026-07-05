#!/usr/bin/env python3
"""
wtr_lab_scraper.py — A fast, Cloudflare-bypassing Python scraper for wtr-lab.com.

Strategy
--------
1. **Cloudflare bypass (Tier 1 — TLS impersonation)**
   Uses `curl_cffi` with `impersonate="chrome"` so the JA3/JA4 TLS fingerprint
   exactly matches a real Chrome 120 client. This defeats Cloudflare's passive
   fingerprinting without ever needing a JS engine.

2. **Cloudflare bypass (Tier 2 — browser headers)**
   Sends the full set of browser-emulation headers (Sec-Fetch-*, Sec-CH-UA-*,
   Accept, Accept-Language, Upgrade-Insecure-Requests, …) ported from the
   NovelDokusha `BrowserHeadersInterceptor`. Many CF edge rules never escalate
   to a JS challenge if the initial request already looks browser-like.

3. **Cloudflare bypass (Tier 3 — challenge detection + retry)**
   Detects Cloudflare interstitials via (a) status codes 202/403/429/502/503,
   (b) the `cf-mitigated` header, (c) body markers like "Just a moment",
   "cf-turnstile", "/cdn-cgi/challenge-platform/", etc. On detection it backs
   off with exponential jitter and retries up to N times.

4. **Local AES-GCM decryption of chapter bodies**
   The wtr-lab `/api/reader/get` endpoint returns chapter text encrypted as
   `arr:<iv_b64>:<tag_b64>:<ct_b64>` (or `str:...` for a single string).
   The AES-256-GCM key was extracted from the site's Next.js chunk
   (`IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru`, 32 bytes). We decrypt locally with
   pycryptodome — no need to round-trip through the `wtr-lab-proxy.fly.dev`
   crutch the Lua scraper uses.

5. **Concurrent batch download**
   `concurrent.futures.ThreadPoolExecutor` with a configurable worker count
   (default 5) plus adaptive backoff on 429s.

Anti-adblocker notes
--------------------
wtr-lab serves an "ad-blocker detected" interstitial to clients whose
fingerprint or behaviour trips its in-house detector. We sidestep this by
looking exactly like Chrome (TLS + headers + cookies), by not sending any
"adblock-detector" pings, and by retrying on the ad-block interstitial body
marker (`adblock` / `ad-block` / `disable_ads`) just like we retry on
Cloudflare challenges.

References
----------
- Lua reference scraper: https://raw.githubusercontent.com/HnDK0/external-sources/refs/heads/main/mtl/wtrlab.lua
- Cloudflare bypass techniques ported from:
  https://github.com/Parasgaming122/NovelDokusha/tree/master/networking/src/main/java/my/noveldokusha/network
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures as cf
import json
import logging
import os
import random
import re
import sys
import threading
import time
import urllib.parse
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

try:
    from curl_cffi import requests as cc_requests
except ImportError as e:  # pragma: no cover
    sys.stderr.write(
        "ERROR: curl_cffi is required. Install it with:\n"
        "  pip install curl_cffi\n"
    )
    raise

try:
    from Crypto.Cipher import AES
except ImportError:
    AES = None  # decryption will fall back to proxy


# ─── Constants ─────────────────────────────────────────────────────────────────

BASE_URL = "https://wtr-lab.com"

# AES-256-GCM key extracted from the wtr-lab.com Next.js chunk
# /_next/static/chunks/f1284758969025b7.js — used to decrypt chapter bodies
# that arrive in the form `arr:<iv>:<tag>:<ct>` or `str:<iv>:<tag>:<ct>`.
WTR_LAB_AES_KEY = b"IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru"  # 32 bytes

# Fallback proxy used by the Lua scraper — only consulted if local
# decryption is unavailable (i.e. pycryptodome not installed).
WTR_LAB_PROXY_URL = "https://wtr-lab-proxy.fly.dev/chapter"

# Chrome 120 on Windows — the same UA string used by curl_cffi's
# `impersonate="chrome"` profile, so the Sec-CH-UA-* hints match the JA3.
DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/120.0.0.0 Safari/537.36"
)

# Browser-like navigation headers — ported from NovelDokusha's
# BrowserHeadersInterceptor. Order matters conceptually: UA must be set first
# so Sec-CH-UA-* can match it; Sec-Fetch-* must be present or CF flags the
# request as a non-browser client.
BROWSER_HEADERS: Dict[str, str] = {
    "Accept": (
        "text/html,application/xhtml+xml,application/xml;q=0.9,"
        "image/avif,image/webp,*/*;q=0.8"
    ),
    "Accept-Language": "en-US,en;q=0.9",
    "Accept-Encoding": "gzip, deflate, br",
    "Cache-Control": "no-cache",
    "Pragma": "no-cache",
    "Upgrade-Insecure-Requests": "1",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Sec-Fetch-User": "?1",
    'Sec-CH-UA': '"Chromium";v="120", "Not(A:Brand";v="24", "Google Chrome";v="120"',
    "Sec-CH-UA-Mobile": "?0",
    "Sec-CH-UA-Platform": '"Windows"',
    "User-Agent": DEFAULT_USER_AGENT,
}

# Header values for the `Server` response header that indicate a Cloudflare
# edge response (ported from NovelDokusha CloudfareVerificationInterceptor).
CF_SERVER_VALUES = (
    "cloudflare-nginx",
    "cloudflare",
    "cloudflare-iad",
    "ddos-guard",
    "ddos-guard.net",
)

# Status codes that Cloudflare (and DDoS-Guard) use to signal a bot challenge.
CF_ERROR_CODES = (202, 403, 429, 502, 503)

# Headers whose mere presence indicates a Cloudflare challenge even on
# non-HTML responses.
CF_CHALLENGE_HEADERS = ("cf-mitigated",)

# Body substrings that identify a CF/DDoS-Guard challenge or wtr-lab's
# anti-adblock interstitial. Sourced from NovelDokusha + trawl + empirically
# observed wtr-lab responses.
#
# IMPORTANT: We deliberately do NOT include generic substrings like
# `/cdn-cgi/challenge-platform/` or `challenge-platform` here, because
# Cloudflare injects a JSD preload script (`/cdn-cgi/challenge-platform/scripts/jsd/main.js`)
# into EVERY page served through Cloudflare's proxy — including the
# legitimate 200-OK responses. Matching on those substrings would flag
# every single wtr-lab page as a challenge, breaking the scraper.
#
# We also do NOT match on bare `adblock` / `disable_ads` because wtr-lab's
# Next.js __NEXT_DATA__ blob contains a `disable_ads` field on every novel
# page. Only match on full adblock-wall sentences.
CF_CHALLENGE_BODY_MARKERS = (
    # CF interstitial-specific markers (only appear on actual challenge pages)
    "cf-browser-verification",
    "cf-challenge-running",
    "cf-please-wait",
    "cf_chl_opt",  # the challenge form field name
    'name="cf_chl_opt"',
    "Just a moment",
    "Checking your browser",
    "Attention Required! | Cloudflare",
    "verify you are human",
    "enable javascript and cookies to continue",
    "one more step",
    'id="challenge-running"',
    'id="cf-challenge-running"',
    'id="turnstile-wrapper"',
    "cf-turnstile",
    "challenges.cloudflare.com/turnstile",
    "ddos-guard.net",
    ".ddos-guard.net",
    # wtr-lab anti-adblock wall — full sentences only
    "Please disable your ad blocker",
    "disable your ad blocker",
    "ad blocker detected",
    "adblocker detected",
)

# Locked to one TLS-impersonating session — sharing the same curl handle
# across threads is safe and lets the CF cookies persist between workers.
_lock = threading.Lock()


# ─── Data models ───────────────────────────────────────────────────────────────

@dataclass
class NovelCard:
    """A lightweight novel reference (search / catalog browse results)."""
    raw_id: str
    slug: str
    title: str
    url: str
    cover: Optional[str] = None
    author: Optional[str] = None
    chapter_count: Optional[int] = None
    status: Optional[str] = None

    @classmethod
    def from_series_item(cls, n: Dict[str, Any]) -> "NovelCard":
        raw_id = str(n.get("raw_id") or n.get("id") or "")
        slug = n.get("slug") or ""
        data = n.get("data") if isinstance(n.get("data"), dict) else {}
        title = data.get("title") or n.get("search_text") or ""
        cover = data.get("image") or None
        author = n.get("author") or data.get("author")
        chapter_count = n.get("chapter_count") or n.get("raw_chapter_count")
        status_code = n.get("status")
        # status is usually 0=ongoing, 1=completed, 2=hiatus, 3=dropped
        status_map = {0: "ongoing", 1: "completed", 2: "hiatus", 3: "dropped"}
        status = status_map.get(status_code, str(status_code) if status_code is not None else None)
        url = f"{BASE_URL}/en/novel/{raw_id}/{slug}" if raw_id and slug else ""
        return cls(
            raw_id=raw_id, slug=slug, title=title, url=url, cover=cover,
            author=author, chapter_count=chapter_count, status=status,
        )


@dataclass
class NovelInfo:
    """Full novel metadata."""
    raw_id: str
    slug: str
    title: str
    url: str
    cover: Optional[str] = None
    author: Optional[str] = None
    description: Optional[str] = None
    genres: List[str] = field(default_factory=list)
    tags: List[str] = field(default_factory=list)
    chapter_count: Optional[int] = None
    raw_chapter_count: Optional[int] = None
    status: Optional[str] = None
    rating: Optional[float] = None
    vote_count: Optional[int] = None
    created_at: Optional[str] = None
    updated_at: Optional[str] = None
    source: Optional[str] = None  # e.g. "fanqienovel"
    ai_enabled: Optional[bool] = None


@dataclass
class Chapter:
    """A chapter reference (no body text)."""
    order: int
    title: str
    url: str
    chapter_id: Optional[int] = None
    name: Optional[str] = None  # original-language name
    updated_at: Optional[str] = None


@dataclass
class ChapterContent:
    """A fully fetched chapter."""
    order: int
    title: str
    url: str
    paragraphs: List[str] = field(default_factory=list)
    encrypted: bool = False
    translated: bool = False


# ─── Exceptions ────────────────────────────────────────────────────────────────

class WtrLabError(Exception):
    """Base class for scraper errors."""


class CloudflareChallengeError(WtrLabError):
    """Raised when a CF challenge cannot be bypassed after N retries."""


class ApiError(WtrLabError):
    """Raised when the wtr-lab API returns `success: false`."""

    def __init__(self, code: Any, message: str):
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message


# ─── Scraper ───────────────────────────────────────────────────────────────────

class WtrLabScraper:
    """
    Synchronous scraper for wtr-lab.com.

    All public methods are safe to call from multiple threads — the underlying
    `curl_cffi` session is guarded by a lock and cookies persist across
    requests. For batch chapter downloads use `download_novel()` which spins
    up a `ThreadPoolExecutor`.
    """

    def __init__(
        self,
        *,
        mode: str = "web",          # "web" (raw) or "ai" (translated)
        max_workers: int = 5,
        max_retries: int = 4,
        base_delay: float = 0.75,
        proxy_fallback: bool = True,
        log_level: str = "INFO",
        locale: str = "en",
    ):
        if mode not in ("web", "ai"):
            raise ValueError(f"mode must be 'web' or 'ai', got: {mode!r}")
        self.mode = mode
        self.max_workers = max_workers
        self.max_retries = max_retries
        self.base_delay = base_delay
        self.proxy_fallback = proxy_fallback
        self.locale = locale.strip("/") or "en"

        self.log = logging.getLogger("wtr_lab")
        if not self.log.handlers:
            h = logging.StreamHandler(sys.stderr)
            h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
            self.log.addHandler(h)
        self.log.setLevel(log_level)

        self._session = cc_requests.Session()
        # Prime the session by hitting the homepage once — this picks up the
        # NEXT_LOCALE cookie and any CF cookies the edge wants to set, so the
        # subsequent API calls don't look "cold".
        try:
            self._session.get(
                f"{BASE_URL}/{self.locale}",
                impersonate="chrome",
                headers=BROWSER_HEADERS,
                timeout=15,
            )
        except Exception as e:
            self.log.debug("priming request failed (non-fatal): %s", e)

    # ─── Low-level HTTP ────────────────────────────────────────────────────

    def _classify_response(self, status: int, headers: Any, body: str) -> bool:
        """Return True if this response looks like a CF / anti-adblock challenge."""
        # (1) cf-mitigated header — strongest signal
        for hname in CF_CHALLENGE_HEADERS:
            try:
                if headers.get(hname) is not None:
                    return True
            except Exception:
                pass
        # (2) status code + Server header — only treat as challenge if BOTH
        # the status is a known CF challenge code AND the response came from
        # a Cloudflare/DDoS-Guard edge. This avoids flagging legitimate 403s
        # (e.g. rate-limit from the origin) or 503s from upstream errors.
        server = (headers.get("Server") or headers.get("server") or "").lower()
        looks_cf = any(s in server for s in CF_SERVER_VALUES)
        if status in CF_ERROR_CODES and looks_cf:
            return True
        # (3) Body markers — only the precise markers in
        # CF_CHALLENGE_BODY_MARKERS (no generic substrings). This catches
        # 200-status managed challenges and adblock walls.
        if body:
            body_lower = body.lower()
            for marker in CF_CHALLENGE_BODY_MARKERS:
                if marker.lower() in body_lower:
                    return True
        return False

    def _request(
        self,
        method: str,
        url: str,
        *,
        json_body: Optional[Dict[str, Any]] = None,
        extra_headers: Optional[Dict[str, str]] = None,
        params: Optional[Dict[str, Any]] = None,
        timeout: int = 30,
    ) -> cc_requests.Response:
        """HTTP request with CF challenge detection + exponential backoff retry."""
        headers = dict(BROWSER_HEADERS)
        if extra_headers:
            headers.update(extra_headers)

        last_exc: Optional[Exception] = None
        for attempt in range(1, self.max_retries + 1):
            try:
                with _lock:
                    resp = self._session.request(
                        method=method,
                        url=url,
                        json=json_body,
                        headers=headers,
                        params=params,
                        impersonate="chrome",
                        timeout=timeout,
                    )
                body_text = resp.text if resp.content else ""
                if self._classify_response(resp.status_code, resp.headers, body_text):
                    raise CloudflareChallengeError(
                        f"CF/anti-adblock challenge (status={resp.status_code}, "
                        f"attempt={attempt}/{self.max_retries}) url={url}"
                    )
                return resp
            except CloudflareChallengeError as e:
                last_exc = e
                # Exponential backoff with jitter
                delay = self.base_delay * (2 ** (attempt - 1)) + random.uniform(0, 0.5)
                self.log.warning("%s — backing off %.2fs", e, delay)
                time.sleep(delay)
            except Exception as e:
                last_exc = e
                delay = self.base_delay * (2 ** (attempt - 1)) + random.uniform(0, 0.5)
                self.log.warning("request error (%s) — retrying in %.2fs", e, delay)
                time.sleep(delay)

        raise WtrLabError(f"request failed after {self.max_retries} retries: {url} — {last_exc}")

    def _get(self, url: str, **kw) -> str:
        return self._request("GET", url, **kw).text

    def _get_json(self, url: str, **kw) -> Any:
        return self._request("GET", url, **kw).json()

    def _post_json(self, url: str, body: Dict[str, Any], **kw) -> Any:
        return self._request("POST", url, json_body=body, **kw).json()

    # ─── Helpers ──────────────────────────────────────────────────────────

    @staticmethod
    def _parse_novel_url(url: str) -> Tuple[str, str]:
        """Extract (raw_id, slug) from a wtr-lab novel or chapter URL."""
        m = re.search(r"/novel/(\d+)/([^/?#]+)", url)
        if not m:
            raise WtrLabError(f"cannot extract novel id/slug from URL: {url}")
        return m.group(1), m.group(2)

    @staticmethod
    def _parse_chapter_url(url: str) -> Tuple[str, str, int]:
        """Extract (raw_id, slug, chapter_no) from a chapter URL."""
        m = re.search(r"/novel/(\d+)/([^/?#]+)/chapter-(\d+)", url)
        if not m:
            raise WtrLabError(f"cannot extract chapter info from URL: {url}")
        return m.group(1), m.group(2), int(m.group(3))

    @staticmethod
    def _extract_next_data(html: str) -> Dict[str, Any]:
        """Pull the Next.js __NEXT_DATA__ JSON blob out of a page."""
        m = re.search(
            r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>',
            html, re.DOTALL,
        )
        if not m:
            raise WtrLabError("could not find __NEXT_DATA__ script in page")
        return json.loads(m.group(1))

    # ─── Decryption ────────────────────────────────────────────────────────

    def _decrypt_body(self, raw: Any) -> Any:
        """
        Decrypt an `arr:...` / `str:...` payload from /api/reader/get.

        Returns:
          - list[str] if the payload was `arr:` (most common — array of paragraphs)
          - str if the payload was `str:`
          - the input unchanged if it's already a list/dict (API occasionally
            returns pre-parsed structures) or if there's nothing to decrypt.
        """
        # Already-decoded by the API (rare; ai-mode sometimes returns a list directly)
        if isinstance(raw, (list, dict)):
            return raw
        if not isinstance(raw, str):
            return raw
        if not (raw.startswith("arr:") or raw.startswith("str:")):
            return raw  # plaintext

        is_arr = raw.startswith("arr:")
        payload = raw[4:]
        parts = payload.split(":")
        if len(parts) != 3:
            raise WtrLabError(f"unexpected encrypted payload shape (parts={len(parts)})")

        iv_b64, tag_b64, ct_b64 = parts
        iv = base64.b64decode(iv_b64)
        tag = base64.b64decode(tag_b64)
        ct = base64.b64decode(ct_b64)

        if AES is not None:
            try:
                cipher = AES.new(WTR_LAB_AES_KEY, AES.MODE_GCM, nonce=iv)
                plaintext = cipher.decrypt_and_verify(ct, tag)
                text = plaintext.decode("utf-8")
                return json.loads(text) if is_arr else text
            except Exception as e:
                self.log.warning("local AES-GCM decrypt failed (%s); falling back to proxy", e)
        else:
            self.log.warning("pycryptodome not installed — using remote proxy for decryption")

        if not self.proxy_fallback:
            raise WtrLabError("decryption failed and proxy fallback disabled")

        # Fallback: round-trip through the public proxy the Lua scraper uses.
        pr = self._session.post(
            WTR_LAB_PROXY_URL,
            json={"payload": raw},
            headers={"Content-Type": "application/json"},
            impersonate="chrome",
            timeout=30,
        )
        pr.raise_for_status()
        result = pr.json()
        # The proxy returns either an array directly or {"body": [...]}
        if isinstance(result, list):
            return result
        if isinstance(result, dict) and "body" in result:
            return result["body"]
        return result

    # ─── Public API: catalog browse ────────────────────────────────────────

    def browse_catalog(self, page: int = 1) -> Tuple[List[NovelCard], bool]:
        """
        Browse the public novel list (`/novel-list?page=N`).

        Returns (cards, has_next).
        """
        url = f"{BASE_URL}/{self.locale}/novel-list"
        html = self._get(url, params={"page": page})
        nd = self._extract_next_data(html)
        series = (nd.get("props", {}).get("pageProps", {}) or {}).get("series") or []
        cards = [NovelCard.from_series_item(n) for n in series if n.get("raw_id")]
        has_next = len(cards) > 0
        return cards, has_next

    def search(self, query: str, page: int = 1) -> Tuple[List[NovelCard], bool]:
        """
        Search novels by free-text query.

        Endpoint: `/novel-finder?text=<query>&page=<page>`
        """
        url = f"{BASE_URL}/{self.locale}/novel-finder"
        html = self._get(url, params={"text": query, "page": page})
        nd = self._extract_next_data(html)
        series = (nd.get("props", {}).get("pageProps", {}) or {}).get("series") or []
        cards = [NovelCard.from_series_item(n) for n in series if n.get("raw_id")]
        has_next = len(cards) > 0
        return cards, has_next

    def browse_catalog_filtered(
        self,
        *,
        order_by: str = "update",
        order: str = "desc",
        status: str = "all",
        release_status: str = "all",
        addition_age: str = "all",
        min_chapters: Optional[str] = None,
        min_rating: Optional[str] = None,
        genres_included: Optional[List[str]] = None,
        genres_excluded: Optional[List[str]] = None,
        tags_included: Optional[List[str]] = None,
        tags_excluded: Optional[List[str]] = None,
        genre_operator: str = "and",
        tag_operator: str = "and",
        page: int = 1,
    ) -> Tuple[List[NovelCard], bool]:
        """
        Browse the catalog with filters. Uses Next.js' `_next/data/<buildId>/…`
        JSON endpoint — fast and returns structured data, no HTML parsing.
        """
        finder_url = f"{BASE_URL}/{self.locale}/novel-finder"
        finder_html = self._get(finder_url)
        m = re.search(r'"buildId":"([^"]+)"', finder_html)
        if not m:
            raise WtrLabError("could not find Next.js buildId on novel-finder page")
        build_id = m.group(1)

        params: List[Tuple[str, Any]] = [
            ("orderBy", order_by),
            ("order", order),
            ("status", status),
            ("release_status", release_status),
            ("addition_age", addition_age),
            ("page", page),
        ]
        if min_chapters:
            params.append(("minc", min_chapters))
        if min_rating:
            params.append(("minr", min_rating))
        if genres_included:
            params.append(("gi", ",".join(genres_included)))
            params.append(("gc", genre_operator))
        if genres_excluded:
            params.append(("ge", ",".join(genres_excluded)))
        if tags_included:
            params.append(("ti", ",".join(tags_included)))
            params.append(("tc", tag_operator))
        if tags_excluded:
            params.append(("te", ",".join(tags_excluded)))

        api_url = (
            f"{BASE_URL}/_next/data/{build_id}/{self.locale}/novel-finder.json"
        )
        data = self._get_json(api_url, params=params)
        page_props = (data.get("pageProps") or {})
        series = page_props.get("series") or []

        seen = set()
        cards: List[NovelCard] = []
        for n in series:
            rid = str(n.get("raw_id") or "")
            if not rid or rid in seen:
                continue
            seen.add(rid)
            cards.append(NovelCard.from_series_item(n))
        return cards, len(cards) > 0

    # ─── Public API: novel info ────────────────────────────────────────────

    # Reverse-lookup tables for genre/tag numeric IDs → human labels.
    # Lifted verbatim from the Lua scraper's filter schema. Used to make
    # NovelInfo.genres/tags human-readable instead of bare integers.
    GENRE_LABELS: Dict[int, str] = {
        1: "Action", 2: "Adult", 3: "Adventure", 4: "Comedy", 5: "Drama",
        6: "Ecchi", 7: "Erciyuan", 8: "Fan-fiction", 9: "Fantasy", 10: "Game",
        11: "Gender Bender", 12: "Harem", 13: "Historical", 14: "Horror",
        15: "Josei", 16: "Martial Arts", 17: "Mature", 18: "Mecha",
        19: "Military", 20: "Mystery", 21: "Psychological", 22: "Romance",
        23: "School Life", 24: "Sci-fi", 25: "Seinen", 26: "Shoujo",
        27: "Shoujo-ai", 28: "Shounen", 29: "Shounen-ai", 30: "Slice of Life",
        31: "Smut", 32: "Sports", 33: "Supernatural", 34: "Tragedy",
        35: "Urban Life", 36: "Wuxia", 37: "Xianxia", 38: "Xuanhuan",
        39: "Yaoi", 40: "Yuri",
    }
    # Tag IDs → labels. Subset of the Lua scraper's tag table; if a tag ID
    # isn't here we fall back to str(id) so nothing is silently dropped.
    TAG_LABELS: Dict[int, str] = {
        5: "Academy", 27: "Alchemy", 30: "Alternate World", 35: "Ancient Times",
        34: "Ancient China", 43: "Antihero Protagonist", 47: "Apocalypse",
        55: "Arranged Marriage", 80: "Beasts", 83: "Betrayal", 93: "Bloodlines",
        95: "Body Tempering", 108: "Business Management", 111: "Calm Protagonist",
        117: "Celebrities", 122: "Cheats", 134: "Clever Protagonist",
        142: "Cold Protagonist", 147: "Calm Protagonist", 154: "Cooking",
        169: "Cultivation", 171: "Cunning Protagonist", 175: "Calm Protagonist",
        191: "Demons", 197: "Determined Protagonist", 198: "Devoted Love Interests",
        208: "Doctors", 211: "Doting Love Interests", 216: "Dragons",
        221: "Dungeons", 225: "Early Romance", 233: "Elves", 246: "Evil Protagonist",
        248: "Evolution", 257: "Family", 264: "Fantasy Creatures",
        265: "Fantasy World", 266: "Farming", 267: "Fast Cultivation",
        268: "Fast Learner", 275: "Female Protagonist", 294: "Futuristic Setting",
        297: "Game Elements", 306: "Genius Protagonist", 312: "God Protagonist",
        315: "Godly Powers", 316: "Gods", 328: "Hard-Working Protagonist",
        329: "Harem-seeking Protagonist", 341: "Hidden Abilities",
        342: "Hiding True Abilities", 343: "Hiding True Identity",
        357: "Immortals", 368: "Interdimensional Travel", 379: "Kingdom Building",
        380: "Kingdoms", 388: "Leadership", 410: "Magic", 414: "Magical Space",
        417: "Male Protagonist", 428: "Master-Disciple Relationship",
        433: "Medical Knowledge", 437: "Military", 446: "Modern Day",
        452: "Monsters", 459: "Multiple Realms", 455: "Multiple Identities",
        473: "Mythology", 474: "Naive Protagonist", 485: "Nobles",
        492: "Older Love Interests", 500: "Overpowered Protagonist",
        505: "Outer Space", 506: "Overpowered Protagonist", 510: "Parallel Worlds",
        536: "Politics", 538: "Polygamy", 539: "Polygamy", 540: "Poor to Rich",
        544: "Post-apocalyptic", 545: "Power Couple", 547: "Pragmatic Protagonist",
        555: "Proactive Protagonist", 560: "Protagonist Strong from Start",
        577: "Reincarnated in Another World", 578: "Reincarnation",
        585: "Revenge", 592: "Romantic Subplot", 594: "Royalty",
        595: "Ruthless Protagonist", 601: "Schemes And Conspiracies",
        606: "Second Chance", 611: "Secretive Protagonist",
        630: "Shameless Protagonist", 640: "Showbiz", 659: "Slow Romance",
        667: "Special Abilities", 681: "Strong Love Interests",
        682: "Strong to Stronger", 692: "Survival", 693: "Survival Game",
        694: "Sword And Magic", 695: "Sword Wielder", 696: "System",
        710: "Time Travel", 717: "Transmigration", 721: "Transported to Another World",
        731: "Underestimated Protagonist", 732: "Unique Cultivation Technique",
        735: "Unlimited Flow", 742: "Virtual Reality", 748: "Wars",
        750: "Weak to Strong", 756: "World Hopping", 765: "Zombies",
        827: "Class Awakening", 828: "Spiritual Energy Revival",
        829: "Reborn", 830: "Reality-Game Fusion", 834: "Time Travel",
    }

    def get_novel_info(self, novel_url_or_id: str, slug: Optional[str] = None) -> NovelInfo:
        """
        Fetch full metadata for a novel.

        Accepts either:
          - a full URL like
            https://wtr-lab.com/en/novel/80730/uma-musume-...
          - a raw_id (e.g. "80730"); the slug is then auto-discovered by
            following the site's 307 redirect from `/en/novel/{id}/_`.
        """
        if novel_url_or_id.isdigit() and not slug:
            # The site 307-redirects any `/en/novel/{id}/<anything>` to the
            # canonical `/en/novel/{id}/{slug}` URL. We use `/_` as a sentinel
            # slug and let curl_cffi follow the redirect.
            probe_url = f"{BASE_URL}/{self.locale}/novel/{novel_url_or_id}/_"
            resp = self._request("GET", probe_url)
            novel_url = str(resp.url)
            raw_id, slug = self._parse_novel_url(novel_url)
        else:
            raw_id, slug_from_url = self._parse_novel_url(novel_url_or_id)
            slug = slug or slug_from_url
            novel_url = f"{BASE_URL}/{self.locale}/novel/{raw_id}/{slug}"

        html = self._get(novel_url)
        nd = self._extract_next_data(html)
        pp = nd.get("props", {}).get("pageProps", {}) or {}
        serie = pp.get("serie", {}) or {}
        sd = serie.get("serie_data", {}) or {}
        inner_data = sd.get("data", {}) or {}

        status_map = {0: "ongoing", 1: "completed", 2: "hiatus", 3: "dropped"}
        genre_ids = sd.get("genres") or []
        tag_ids = sd.get("tags") or []
        genres = [self.GENRE_LABELS.get(g, str(g)) for g in genre_ids]
        tags = [self.TAG_LABELS.get(t, str(t)) for t in tag_ids]

        rating_value = sd.get("rating")
        try:
            rating_float = float(rating_value) if rating_value is not None else None
        except (TypeError, ValueError):
            rating_float = None

        return NovelInfo(
            raw_id=str(raw_id),
            slug=slug,
            title=inner_data.get("title") or "",
            url=novel_url,
            cover=inner_data.get("image"),
            author=sd.get("author") or inner_data.get("author"),
            description=inner_data.get("description"),
            genres=genres,
            tags=tags,
            chapter_count=sd.get("chapter_count"),
            raw_chapter_count=sd.get("raw_chapter_count"),
            status=status_map.get(sd.get("status"), str(sd.get("status")) if sd.get("status") is not None else None),
            rating=rating_float,
            vote_count=sd.get("vote"),
            created_at=sd.get("created_at"),
            updated_at=sd.get("updated_at"),
            source=(serie.get("raws") or [{}])[0].get("slug") if serie.get("raws") else None,
            ai_enabled=sd.get("ai_enabled"),
        )

    # ─── Public API: chapter list ──────────────────────────────────────────

    def get_chapter_list(self, novel_url_or_id: str, slug: Optional[str] = None) -> List[Chapter]:
        """
        Fetch the full chapter listing for a novel via `/api/chapters/{raw_id}`.
        """
        if novel_url_or_id.isdigit() and not slug:
            # Reuse the same redirect trick as get_novel_info to recover slug
            probe_url = f"{BASE_URL}/{self.locale}/novel/{novel_url_or_id}/_"
            resp = self._request("GET", probe_url)
            novel_url = str(resp.url)
            raw_id, slug = self._parse_novel_url(novel_url)
        else:
            raw_id, slug_from_url = self._parse_novel_url(novel_url_or_id)
            slug = slug or slug_from_url
            novel_url = f"{BASE_URL}/{self.locale}/novel/{raw_id}/{slug}"

        api_url = f"{BASE_URL}/api/chapters/{raw_id}"
        data = self._get_json(api_url, extra_headers={"Referer": novel_url})
        chapters_raw = (data or {}).get("chapters") or []

        chapters: List[Chapter] = []
        for i, ch in enumerate(chapters_raw):
            order = ch.get("order") or (i + 1)
            title = ch.get("title") or f"Chapter {order}"
            ch_url = (
                f"{BASE_URL}/{self.locale}/novel/{raw_id}/{slug}/chapter-{order}?service=web"
            )
            chapters.append(Chapter(
                order=order,
                title=title,
                url=ch_url,
                chapter_id=ch.get("id"),
                name=ch.get("name"),
                updated_at=ch.get("updated_at"),
            ))
        return chapters

    # ─── Public API: chapter content ───────────────────────────────────────

    def get_chapter(self, chapter_url: str) -> ChapterContent:
        """
        Fetch the full text of one chapter.

        `chapter_url` must match:
          https://wtr-lab.com/en/novel/<raw_id>/<slug>/chapter-<N>?service=web
        """
        raw_id, slug, chapter_no = self._parse_chapter_url(chapter_url)
        # Normalize the canonical chapter URL — keeps the Referer header that
        # wtr-lab's API expects consistent regardless of how the caller
        # formatted the input.
        canonical_url = (
            f"{BASE_URL}/{self.locale}/novel/{raw_id}/{slug}/chapter-{chapter_no}?service=web"
        )

        body_payload = {
            "translate": self.mode,    # "web" (raw) or "ai" (translated)
            "language": "none",
            "raw_id": raw_id,
            "chapter_no": chapter_no,
            "retry": False,
            "force_retry": False,
        }

        # The API returns success=false on translator warmup misses; we retry
        # with `retry: true` once to trigger server-side regeneration.
        for attempt, payload in enumerate(
            (body_payload, {**body_payload, "retry": True})
        ):
            data = self._post_json(
                f"{BASE_URL}/api/reader/get",
                payload,
                extra_headers={
                    "Content-Type": "application/json",
                    "Referer": canonical_url,
                    "Origin": BASE_URL,
                    "Accept": "application/json, text/plain, */*",
                    "Sec-Fetch-Dest": "empty",
                    "Sec-Fetch-Mode": "cors",
                    "Sec-Fetch-Site": "same-origin",
                },
            )
            if isinstance(data, dict) and data.get("success") is False:
                # If the failure looks transient (translator warmup), retry once
                if attempt == 0 and "translat" in (data.get("error") or "").lower():
                    self.log.info("translator warmup miss for ch %d, retrying", chapter_no)
                    time.sleep(1.0)
                    continue
                raise ApiError(data.get("code"), data.get("error") or "unknown API error")
            break

        # Response shape: { success, chapter, data: { raw_id, chapter_id, status, data: {...} } }
        inner = (data.get("data") or {}).get("data") or {}
        if not inner:
            raise WtrLabError(f"empty data field in /api/reader/get response for {canonical_url}")

        raw_body = inner.get("body")
        if raw_body is None:
            raise WtrLabError(f"no body field in /api/reader/get response for {canonical_url}")

        decrypted = self._decrypt_body(raw_body)
        if isinstance(decrypted, list):
            paragraphs = [p for p in decrypted if isinstance(p, str) and p.strip()]
        elif isinstance(decrypted, str):
            paragraphs = [p for p in decrypted.split("\n") if p.strip()]
        else:
            paragraphs = []

        # In ai-mode, apply glossary term substitution if glossary_data is present.
        if self.mode == "ai" and isinstance(decrypted, list):
            paragraphs = self._apply_glossary(paragraphs, inner.get("glossary_data"))

        # Clean up translator/editor watermarks and chapter headers that
        # appear at the start of a paragraph.
        paragraphs = [self._clean_paragraph(p) for p in paragraphs]
        paragraphs = [p for p in paragraphs if p]

        title = inner.get("title") or f"Chapter {chapter_no}"
        # In ai-mode the title may also contain glossary markers (e.g.
        # "Chapter 1: The Name is, ※1forge") — apply the same substitution.
        if self.mode == "ai" and isinstance(decrypted, list):
            title = self._apply_glossary([title], inner.get("glossary_data"))[0]
        return ChapterContent(
            order=chapter_no,
            title=title,
            url=canonical_url,
            paragraphs=paragraphs,
            encrypted=inner.get("encrypted", False),
            translated=(self.mode == "ai"),
        )

    @staticmethod
    def _clean_paragraph(text: str) -> str:
        """Strip translator/editor watermarks and chapter-heading prefixes."""
        text = text.strip("\uFEFF \t\r\n")
        # Strip "Chapter N" / "第N章" prefix
        text = re.sub(r"^\s*(Chapter\s+\d+|第\s*\d+\s*章)[^\n\r]*", "", text).strip()
        # Strip "Translator:" / "Editor:" watermarks
        text = re.sub(
            r"^\s*(Translator|Editor|Proofreader|Read\s+(at|on|latest))\s*[:\s][^\n\r]{0,70}",
            "", text, flags=re.IGNORECASE,
        ).strip()
        return text

    def _apply_glossary(self, paragraphs: List[str], glossary_data: Optional[Dict[str, Any]]) -> List[str]:
        """
        In ai-mode, the body contains markers like `※<idx>forge` and `※<idx>〓`
        that need to be replaced with the corresponding glossary term. The
        glossary_data.terms array is 0-indexed: idx == position in the array.
        Each term entry is [raw_translation, original_chinese].
        """
        if not glossary_data or not isinstance(glossary_data, dict):
            return paragraphs
        terms = glossary_data.get("terms") or []
        if not terms:
            return paragraphs

        # Build {idx: term} mapping. terms[i] → idx i (0-based)
        glossary: Dict[int, str] = {}
        for i, entry in enumerate(terms):
            if isinstance(entry, list) and entry:
                glossary[i] = entry[0] or ""

        out: List[str] = []
        for p in paragraphs:
            for idx, term in glossary.items():
                if not term:
                    continue
                # Two marker styles observed in the wild: ※<idx>forge and ※<idx>〓
                p = p.replace(f"※{idx}\u26ec", term)
                p = p.replace(f"※{idx}\u3013", term)
            out.append(p)
        return out

    # ─── Public API: full novel download ───────────────────────────────────

    def download_novel(
        self,
        novel_url_or_id: str,
        output_dir: Optional[Path] = None,
        *,
        start_chapter: Optional[int] = None,
        end_chapter: Optional[int] = None,
        chapter_urls: Optional[List[str]] = None,
        progress: bool = True,
    ) -> Path:
        """
        Download all (or a range of) chapters of a novel into a single JSON file.

        Args:
            novel_url_or_id: novel URL or raw_id.
            output_dir: where to save the JSON. Defaults to /home/z/my-project/download/.
            start_chapter, end_chapter: inclusive 1-based chapter range to download.
                If omitted, downloads every chapter.
            chapter_urls: explicit list of chapter URLs (overrides range).
            progress: print per-chapter progress to stderr.

        Returns the path to the saved JSON file.
        """
        output_dir = output_dir or Path("/home/z/my-project/download")
        output_dir.mkdir(parents=True, exist_ok=True)

        info = self.get_novel_info(novel_url_or_id)
        self.log.info("novel: %s (raw_id=%s, chapters=%s)",
                      info.title, info.raw_id, info.chapter_count)

        if chapter_urls is None:
            chapters = self.get_chapter_list(info.url)
            if start_chapter is not None or end_chapter is not None:
                lo = start_chapter or 1
                hi = end_chapter or (chapters[-1].order if chapters else 0)
                chapters = [c for c in chapters if lo <= c.order <= hi]
        else:
            chapters = []
            for u in chapter_urls:
                rid, sl, no = self._parse_chapter_url(u)
                chapters.append(Chapter(order=no, title=f"Chapter {no}", url=u))

        if not chapters:
            raise WtrLabError("no chapters to download (check the range / chapter_urls)")

        self.log.info("downloading %d chapters with %d workers",
                      len(chapters), self.max_workers)

        results: Dict[int, ChapterContent] = {}
        errors: Dict[int, str] = {}
        done = 0
        total = len(chapters)
        t0 = time.time()

        def _fetch(ch: Chapter) -> Tuple[int, Optional[ChapterContent], Optional[str]]:
            try:
                content = self.get_chapter(ch.url)
                return ch.order, content, None
            except Exception as e:
                return ch.order, None, str(e)

        with cf.ThreadPoolExecutor(max_workers=self.max_workers) as pool:
            futures = {pool.submit(_fetch, ch): ch for ch in chapters}
            for fut in cf.as_completed(futures):
                order, content, err = fut.result()
                done += 1
                if err:
                    errors[order] = err
                    self.log.warning("ch %d failed: %s", order, err)
                else:
                    results[order] = content  # type: ignore[assignment]
                if progress:
                    elapsed = time.time() - t0
                    rate = done / elapsed if elapsed > 0 else 0
                    sys.stderr.write(
                        f"\r  [{done}/{total}] ch {order} "
                        f"({rate:.1f} ch/s, {len(errors)} errors)   "
                    )
                    sys.stderr.flush()
        if progress:
            sys.stderr.write("\n")

        # Sort chapters by order
        sorted_contents = [results[k] for k in sorted(results.keys())]

        out = {
            "novel": asdict(info),
            "mode": self.mode,
            "chapters_downloaded": len(sorted_contents),
            "chapters_failed": len(errors),
            "errors": errors,
            "chapters": [asdict(c) for c in sorted_contents],
            "scraped_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "scraper_version": "1.0.0",
        }

        safe_title = re.sub(r"[^\w\-]+", "_", info.title or info.raw_id)[:80]
        filename = f"{info.raw_id}_{safe_title}.json"
        out_path = output_dir / filename
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)

        self.log.info("saved %d chapters → %s", len(sorted_contents), out_path)
        return out_path


# ─── CLI ───────────────────────────────────────────────────────────────────────

def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="wtr_lab_scraper",
        description="Fast, Cloudflare-bypassing Python scraper for wtr-lab.com",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
Examples:
  # Search for novels
  python wtr_lab_scraper.py search "uma musume"

  # Get novel metadata
  python wtr_lab_scraper.py info https://wtr-lab.com/en/novel/80730/uma-musume-...
  python wtr_lab_scraper.py info 80730

  # List chapters
  python wtr_lab_scraper.py chapters https://wtr-lab.com/en/novel/80730/uma-musume-...

  # Fetch a single chapter
  python wtr_lab_scraper.py chapter \\
      "https://wtr-lab.com/en/novel/80730/uma-musume-.../chapter-1?service=web"

  # Download the whole novel to JSON (5 concurrent workers)
  python wtr_lab_scraper.py download https://wtr-lab.com/en/novel/80730/uma-musume-...

  # Download chapters 10-50
  python wtr_lab_scraper.py download 80730 --start 10 --end 50

  # Browse the catalog (latest-updated page 1)
  python wtr_lab_scraper.py catalog --page 1

  # Filtered catalog (Action+Fantasy, ongoing, 100+ chapters)
  python wtr_lab_scraper.py catalog \\
      --genre 1,9 --status ongoing --min-chapters 100
""",
    )
    p.add_argument("--mode", choices=("web", "ai"), default="web",
                   help="translation mode: 'web' = raw source, 'ai' = AI-translated (default: web)")
    p.add_argument("--workers", type=int, default=5,
                   help="concurrent workers for batch downloads (default: 5)")
    p.add_argument("--log-level", default="INFO",
                   choices=("DEBUG", "INFO", "WARNING", "ERROR"))
    p.add_argument("--retries", type=int, default=4,
                   help="max retries on CF challenge / network error (default: 4)")
    p.add_argument("--locale", default="en", help="site locale (default: en)")
    p.add_argument("--no-proxy-fallback", action="store_true",
                   help="don't fall back to wtr-lab-proxy.fly.dev if local decryption fails")

    sub = p.add_subparsers(dest="command", required=True)

    # search
    sp = sub.add_parser("search", help="search novels by free-text query")
    sp.add_argument("query", help="search string")
    sp.add_argument("--page", type=int, default=1)
    sp.add_argument("--json", action="store_true", help="emit JSON instead of plain text")

    # info
    sp = sub.add_parser("info", help="fetch novel metadata")
    sp.add_argument("novel", help="novel URL or raw_id")
    sp.add_argument("--json", action="store_true", default=True, help="emit JSON (default)")

    # chapters
    sp = sub.add_parser("chapters", help="list chapters for a novel")
    sp.add_argument("novel", help="novel URL or raw_id")
    sp.add_argument("--json", action="store_true", help="emit JSON instead of plain text")

    # chapter
    sp = sub.add_parser("chapter", help="fetch a single chapter's text")
    sp.add_argument("url", help="chapter reader URL (must contain /chapter-N)")
    sp.add_argument("--json", action="store_true", help="emit JSON instead of plain text")

    # download
    sp = sub.add_parser("download", help="download all (or a range of) chapters to a single JSON")
    sp.add_argument("novel", help="novel URL or raw_id")
    sp.add_argument("--start", type=int, default=None, help="start chapter (1-based, inclusive)")
    sp.add_argument("--end", type=int, default=None, help="end chapter (1-based, inclusive)")
    sp.add_argument("--output-dir", default="/home/z/my-project/download",
                    help="where to save the JSON output")

    # catalog
    sp = sub.add_parser("catalog", help="browse / filter the novel catalog")
    sp.add_argument("--page", type=int, default=1)
    sp.add_argument("--order-by", default="update",
                    choices=("update", "date", "random", "weekly_rank", "monthly_rank",
                             "view", "name", "reader", "chapter", "rating",
                             "total_rate", "vote"))
    sp.add_argument("--order", default="desc", choices=("desc", "asc"))
    sp.add_argument("--status", default="all",
                    choices=("all", "ongoing", "completed", "hiatus", "dropped"))
    sp.add_argument("--release-status", default="all",
                    choices=("all", "released", "voting"))
    sp.add_argument("--addition-age", default="all",
                    choices=("all", "day", "week", "month"))
    sp.add_argument("--min-chapters", default=None)
    sp.add_argument("--min-rating", default=None)
    sp.add_argument("--genre", default=None,
                    help="comma-separated genre IDs (e.g. 1,9 for Action+Fantasy)")
    sp.add_argument("--tag", default=None,
                    help="comma-separated tag IDs")
    sp.add_argument("--genre-operator", default="and", choices=("and", "or"))
    sp.add_argument("--tag-operator", default="and", choices=("and", "or"))
    sp.add_argument("--json", action="store_true", help="emit JSON instead of plain text")

    return p


def _print_card_table(cards: List[NovelCard]) -> None:
    if not cards:
        print("(no results)")
        return
    # Compute column widths
    id_w = max(len(c.raw_id) for c in cards + [NovelCard("", "", "", "")])
    ch_w = max(len(str(c.chapter_count or "-")) for c in cards + [NovelCard("", "", "", "")])
    title_w = min(60, max(len(c.title[:60]) for c in cards))
    print(f"{'ID'.ljust(id_w)}  {'Ch'.rjust(ch_w)}  {'Status'.ljust(10)}  Title")
    print(f"{'-'*id_w}  {'-'*ch_w}  {'-'*10}  {'-'*title_w}")
    for c in cards:
        ch = str(c.chapter_count or "-")
        st = (c.status or "-")[:10]
        title = c.title[:60]
        print(f"{c.raw_id.ljust(id_w)}  {ch.rjust(ch_w)}  {st.ljust(10)}  {title}")
        print(f"  → {c.url}")


def main(argv: Optional[List[str]] = None) -> int:
    args = _build_parser().parse_args(argv)

    scraper = WtrLabScraper(
        mode=args.mode,
        max_workers=args.workers,
        max_retries=args.retries,
        log_level=args.log_level,
        locale=args.locale,
        proxy_fallback=not args.no_proxy_fallback,
    )

    if args.command == "search":
        cards, has_next = scraper.search(args.query, page=args.page)
        if args.json:
            print(json.dumps([asdict(c) for c in cards], ensure_ascii=False, indent=2))
        else:
            _print_card_table(cards)
            print(f"\n(has_next={has_next})")
        return 0

    if args.command == "info":
        info = scraper.get_novel_info(args.novel)
        print(json.dumps(asdict(info), ensure_ascii=False, indent=2))
        return 0

    if args.command == "chapters":
        chapters = scraper.get_chapter_list(args.novel)
        if args.json:
            print(json.dumps([asdict(c) for c in chapters], ensure_ascii=False, indent=2))
        else:
            print(f"{len(chapters)} chapters:")
            for ch in chapters:
                print(f"  [{ch.order:>5}] {ch.title}")
                print(f"          {ch.url}")
        return 0

    if args.command == "chapter":
        content = scraper.get_chapter(args.url)
        if args.json:
            print(json.dumps(asdict(content), ensure_ascii=False, indent=2))
        else:
            print(f"# Chapter {content.order}: {content.title}")
            print(f"# URL: {content.url}")
            print()
            for p in content.paragraphs:
                print(p)
                print()
        return 0

    if args.command == "download":
        out_path = scraper.download_novel(
            args.novel,
            output_dir=Path(args.output_dir),
            start_chapter=args.start,
            end_chapter=args.end,
        )
        print(f"\nSaved → {out_path}")
        return 0

    if args.command == "catalog":
        # Decide whether to use filtered endpoint
        use_filtered = any([
            args.order_by != "update", args.order != "desc", args.status != "all",
            args.release_status != "all", args.addition_age != "all",
            args.min_chapters, args.min_rating, args.genre, args.tag,
        ])
        if use_filtered:
            cards, has_next = scraper.browse_catalog_filtered(
                order_by=args.order_by, order=args.order, status=args.status,
                release_status=args.release_status, addition_age=args.addition_age,
                min_chapters=args.min_chapters, min_rating=args.min_rating,
                genres_included=args.genre.split(",") if args.genre else None,
                tags_included=args.tag.split(",") if args.tag else None,
                genre_operator=args.genre_operator, tag_operator=args.tag_operator,
                page=args.page,
            )
        else:
            cards, has_next = scraper.browse_catalog(page=args.page)
        if args.json:
            print(json.dumps([asdict(c) for c in cards], ensure_ascii=False, indent=2))
        else:
            _print_card_table(cards)
            print(f"\n(has_next={has_next})")
        return 0

    return 2


if __name__ == "__main__":
    sys.exit(main())
