package com.stryker.terminal.ui.customize

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.stryker.terminal.R
import com.stryker.terminal.component.ComponentManager
import com.stryker.terminal.component.colorscheme.ColorSchemeComponent
import com.stryker.terminal.component.config.NeoPreference
import com.stryker.terminal.component.config.NeoTermPath
import com.stryker.terminal.component.font.FontComponent
import com.stryker.terminal.frontend.session.view.TerminalView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class CustomizeActivity : BaseCustomizeActivity() {
  private val REQUEST_SELECT_FONT = 22222
  private val REQUEST_SELECT_COLOR = 22223

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    initCustomizationComponent(R.layout.ui_customize)

    findViewById<View>(R.id.custom_install_font_button).setOnClickListener {
      val intent = Intent()
      intent.action = Intent.ACTION_GET_CONTENT
      intent.type = "*/*"
      startActivityForResult(Intent.createChooser(intent, getString(R.string.install_font)), REQUEST_SELECT_FONT)
    }
    findViewById<TextView>(R.id.text_size).text = "Text size: ${NeoPreference.getFontSize()}"
    findViewById<View>(R.id.custom_install_color_button).setOnClickListener {
      val intent = Intent()
      intent.action = Intent.ACTION_GET_CONTENT
      intent.type = "*/*"
      startActivityForResult(
        Intent.createChooser(intent, getString(R.string.install_color)),
        REQUEST_SELECT_COLOR
      )
    }
    val term = findViewById<TerminalView>(R.id.terminal_view)
    term.textSize = NeoPreference.getFontSize()
    term.setOnTextSize { size ->
      findViewById<TextView>(R.id.text_size).text = "Text size: $size"

    }

  }

  private fun setupSpinners() {
    val fontComponent = ComponentManager.getComponent<FontComponent>()
    val colorSchemeComponent = ComponentManager.getComponent<ColorSchemeComponent>()

    setupSpinner(R.id.custom_font_spinner, fontComponent.getFontNames(),
      fontComponent.getCurrentFontName(), object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
        }

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
          val fontName = parent!!.adapter!!.getItem(position) as String
          val font = fontComponent.getFont(fontName)
          fontComponent.applyFont(terminalView, extraKeysView, font)
          fontComponent.setCurrentFont(fontName)
        }
      })

    val colorData = listOf(
      getString(R.string.new_color_scheme),
      *colorSchemeComponent.getColorSchemeNames().toTypedArray()
    )
    setupSpinner(R.id.custom_color_spinner, colorData,
      colorSchemeComponent.getCurrentColorSchemeName(), object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
        }

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
          if (position == 0) {
            val intent = Intent(this@CustomizeActivity, ColorSchemeActivity::class.java)
            startActivity(intent)
            return
          }
          val colorName = parent!!.adapter!!.getItem(position) as String
          val color = colorSchemeComponent.getColorScheme(colorName)
          colorSchemeComponent.applyColorScheme(terminalView, extraKeysView, color)
          colorSchemeComponent.setCurrentColorScheme(colorName)
        }
      })
  }

  private fun setupSpinner(
    id: Int,
    data: List<String>,
    selected: String,
    listener: AdapterView.OnItemSelectedListener
  ): Spinner {
    val spinner = findViewById<Spinner>(id)
    val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, data)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
    spinner.onItemSelectedListener = listener
    spinner.setSelection(if (data.contains(selected)) data.indexOf(selected) else 0)
    return spinner
  }

  override fun onResume() {
    super.onResume()
    setupSpinners()
  }

  override fun onDestroy() {
    super.onDestroy()
    session.finishIfRunning()
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    val uri = data?.data
    if (resultCode == RESULT_OK && uri != null) {
      when (requestCode) {
        REQUEST_SELECT_FONT -> installFileTo(uri, NeoTermPath.FONT_PATH)
        REQUEST_SELECT_COLOR -> installFileTo(uri, NeoTermPath.COLORS_PATH)
      }
      setupSpinners()
    }
    super.onActivityResult(requestCode, resultCode, data)
  }

  private fun installFileTo(uri: Uri, targetDir: String) {
    kotlin.runCatching {
      val name = displayNameOf(uri) ?: throw IOException("No file name for $uri")
      File(targetDir).mkdirs()
      val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
      input.use { source -> FileOutputStream(File(targetDir, name)).use(source::copyTo) }
    }.onFailure {
      Toast.makeText(this, getString(R.string.error) + ": ${it.localizedMessage}", Toast.LENGTH_LONG).show()
    }
  }

  private fun displayNameOf(uri: Uri): String? {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (column != -1 && it.moveToFirst()) return it.getString(column)
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      android.R.id.home -> finish()
    }
    return item.let { super.onOptionsItemSelected(it) }
  }
}
