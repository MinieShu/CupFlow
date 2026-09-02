package com.cupflow.companion

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class IngredientManagementActivity : Activity() {
    private val referencePhotoRequest = 6301
    private val store by lazy { IngredientGridStore(this) }
    private lateinit var nameInput: EditText
    private lateinit var rowsInput: EditText
    private lateinit var columnsInput: EditText
    private lateinit var editor: LinearLayout
    private lateinit var cards: LinearLayout
    private lateinit var status: TextView
    private var editing: IngredientGrid? = null
    private var cellInputs = emptyList<EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        renderCards()
    }

    @Deprecated("Uses the system camera for the optional grid reference photo")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != referencePhotoRequest || resultCode != RESULT_OK) return
        val grid = editing ?: return
        val bitmap = data?.extras?.get("data") as? Bitmap ?: run { setStatus("未获取到基准图。") ; return }
        val directory = File(filesDir, "ingredient-grid-references").apply { mkdirs() }
        val file = File(directory, "${grid.id}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        editing = grid.copy(referencePath = file.absolutePath)
        store.save(editing!!)
        setStatus("已保存 ${grid.name} 的基准图。")
        renderCards()
    }

    private fun buildView(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 32); setBackgroundColor(0xfff8f6ef.toInt()) }
        body.addView(text("配料管理", 26f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(text("只配置难以直识的小料格架；带标签的独立物料由视觉直接读取。", 13f, 0xff71817b.toInt()))
        status = text("", 13f, 0xff164f3f.toInt()).apply { setPadding(14, 10, 14, 10); background = rounded(0xffe6f3e8.toInt(), 0xffe6f3e8.toInt()) }
        body.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14; bottomMargin = 10 })

        val setup = card("新建或编辑小料格架", "行列可自定义；每格填写实际存放的小料。")
        nameInput = EditText(this).apply { hint = "格架名称，例如：操作台小料盒" }
        rowsInput = EditText(this).apply { hint = "行数，例如：3"; inputType = 2 }
        columnsInput = EditText(this).apply { hint = "列数，例如：4"; inputType = 2 }
        setup.addView(nameInput)
        setup.addView(rowsInput)
        setup.addView(columnsInput)
        setup.addView(button("新建格架") { resetEditor() })
        setup.addView(button("生成格位", true) { createEditor() })
        editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setup.addView(editor)
        setup.addView(button("保存格架") { saveGrid() })
        setup.addView(button("拍摄基准图（可选）") { captureReference() })
        body.addView(setup)

        body.addView(text("已保存的格架", 19f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14; bottomMargin = 8 })
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(cards)
        return ScrollView(this).apply { addView(body) }
    }

    private fun createEditor() {
        val rows = rowsInput.text.toString().toIntOrNull()
        val columns = columnsInput.text.toString().toIntOrNull()
        if (rows == null || columns == null || rows !in 1..8 || columns !in 1..8) { setStatus("行数和列数需在 1 到 8 之间。") ; return }
        val previous = cellInputs.map { it.text.toString() }
        val inputs = mutableListOf<EditText>()
        editor.removeAllViews()
        repeat(rows) { row ->
            editor.addView(text("第 ${row + 1} 行", 14f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(columns) { column ->
                val index = row * columns + column
                val input = EditText(this).apply { hint = "${row + 1}-${column + 1}"; setText(previous.getOrNull(index).orEmpty()); textSize = 13f }
                rowView.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 6 })
                inputs += input
            }
            editor.addView(rowView)
        }
        cellInputs = inputs
        setStatus("请逐格填写物料名，例如：珍珠、椰果。")
    }

    private fun resetEditor() {
        editing = null
        nameInput.text.clear()
        rowsInput.text.clear()
        columnsInput.text.clear()
        cellInputs = emptyList()
        editor.removeAllViews()
        setStatus("正在新建格架；保存后会作为独立格架保留。")
    }

    private fun saveGrid(): Boolean {
        val name = nameInput.text.toString().trim()
        val rows = rowsInput.text.toString().toIntOrNull()
        val columns = columnsInput.text.toString().toIntOrNull()
        if (name.isBlank() || rows == null || columns == null || rows !in 1..8 || columns !in 1..8 || cellInputs.size != rows * columns) {
            setStatus("请填写名称、行列，并生成全部格位。")
            return false
        }
        val grid = IngredientGrid(editing?.id ?: "grid-${System.currentTimeMillis()}", name, rows, columns, cellInputs.map { it.text.toString().trim() }, editing?.referencePath)
        store.save(grid)
        editing = grid
        setStatus("已保存 $name。")
        renderCards()
        return true
    }

    private fun captureReference() {
        if (!saveGrid()) {
            Toast.makeText(this, "请先填写名称、行列和格位，再拍摄基准图。", Toast.LENGTH_LONG).show()
            return
        }
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(packageManager) == null) {
            setStatus("未找到可用的系统相机。")
            Toast.makeText(this, "未找到可用的系统相机。", Toast.LENGTH_LONG).show()
            return
        }
        setStatus("正在打开系统相机…")
        try {
            startActivityForResult(cameraIntent, referencePhotoRequest)
        } catch (error: Exception) {
            setStatus("无法打开相机：${error.message.orEmpty()}")
            Toast.makeText(this, "无法打开系统相机。", Toast.LENGTH_LONG).show()
        }
    }

    private fun editGrid(grid: IngredientGrid) {
        editing = grid
        nameInput.setText(grid.name)
        rowsInput.setText(grid.rows.toString())
        columnsInput.setText(grid.columns.toString())
        cellInputs = grid.cells.map { EditText(this).apply { setText(it) } }
        createEditor()
        setStatus("正在编辑 ${grid.name}。")
    }

    private fun renderCards() {
        cards.removeAllViews()
        val grids = store.all()
        if (grids.isEmpty()) {
            cards.addView(text("尚未配置格架。若小料视觉识别不稳定，可先建立一个格架。", 14f, 0xff71817b.toInt()))
            return
        }
        grids.forEach { grid ->
            val card = card(grid.name, "${grid.rows} × ${grid.columns} · ${if (grid.referencePath == null) "未拍基准图（仍可使用）" else "已拍基准图"}")
            card.addView(text(grid.cells.mapIndexed { index, name -> "${index / grid.columns + 1}-${index % grid.columns + 1}:${name.ifBlank { "未填写" }}" }.joinToString("  "), 13f))
            card.addView(button("编辑此格架", true) { editGrid(grid) })
            cards.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        }
    }

    private fun card(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20); background = rounded(0xfffffdf8.toInt(), 0xffe1e5da.toInt())
        addView(text(title, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        addView(text(subtitle, 12f, 0xff71817b.toInt()), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(if (primary) Color.WHITE else 0xff164f3f.toInt())
        background = rounded(if (primary) 0xff164f3f.toInt() else 0xffeff6e9.toInt(), if (primary) 0xff164f3f.toInt() else 0xffbfc8bd.toInt())
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: Int = 0xff10241f.toInt()) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setLineSpacing(5f, 1f) }
    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply { setColor(fill); setStroke(2, stroke); cornerRadius = 22f }
    private fun setStatus(value: String) { status.text = value }
}
