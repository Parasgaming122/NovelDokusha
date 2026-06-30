package my.noveldoksuha.coreui

import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.asLiveData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.drop
import my.noveldoksuha.coreui.mappers.toTheme
import my.noveldoksuha.coreui.theme.ThemeProvider
import my.noveldoksuha.coreui.theme.Themes
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.Toasty
import javax.inject.Inject

@AndroidEntryPoint
open class BaseActivity : AppCompatActivity() {

    // C1 fix: previously `by lazy { AppPreferences(applicationContext) }` constructed a second
    // AppPreferences instance separate from the Hilt @Singleton. Both shared the same backing
    // SharedPreferences, but the OnSharedPreferenceChangeListener flow machinery was duplicated.
    // Now we inject the singleton — only one instance lives in the app.
    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var themeProvider: ThemeProvider

    @Inject
    lateinit var toasty: Toasty

    private fun getAppTheme(): Int {
        val theme = appPreferences.THEME_ID.value.toTheme
        if (!appPreferences.THEME_FOLLOW_SYSTEM.value)
            return theme.themeId

        val isSystemThemeLight = !isSystemInDarkTheme()
        if (isSystemThemeLight && !theme.isLight) return Themes.LIGHT.themeId
        if (!isSystemThemeLight && theme.isLight) return Themes.DARK.themeId
        return theme.themeId
    }

    private fun isSystemInDarkTheme(): Boolean {
        val uiMode = resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // This will remain until Reader Screen has no View XML usages
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getAppTheme())
        appPreferences.THEME_ID.flow().drop(1).asLiveData().observe(this) { recreate() }
        appPreferences.THEME_FOLLOW_SYSTEM.flow().drop(1).asLiveData().observe(this) { recreate() }
        super.onCreate(savedInstanceState)
    }
}