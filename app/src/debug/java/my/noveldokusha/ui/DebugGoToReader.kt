package my.noveldokusha.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import my.noveldokusha.R
import my.noveldokusha.features.reader.ReaderActivity

class DebugGoToReader : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_middleware_test)

        startActivity(
            ReaderActivity.IntentData(
                this,
                bookUrl = "https://readnovelfull.com/lord-of-the-mysteries.html",
                chapterUrl = "https://readnovelfull.com/lord-of-the-mysteries/chapter-1-prologue.html"
            )
        )
    }
}
