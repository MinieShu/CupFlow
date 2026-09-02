package com.cupflow.companion

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RecipeManagementActivity : Activity() {
    private val imageRequest = 5101
    private val store by lazy { RecipeStore(this) }
    private lateinit var cards: LinearLayout
    private lateinit var importText: EditText
    private lateinit var status: TextView
    private var selected: DrinkRecipe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        renderCards()
    }

    @Deprecated("Uses the system document picker for local recipe images")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != imageRequest || resultCode != RESULT_OK) return
        val recipe = selected ?: run { setStatus("请先点选一张配方卡片。") ; return }
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        store.save(recipe.copy(imageUri = uri.toString()))
        selected = recipe.copy(imageUri = uri.toString())
        setStatus("已为 ${recipe.drink} 设置图片。")
        renderCards()
    }

    private fun buildView(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 28) }
        body.setBackgroundColor(0xfff8f6ef.toInt())
        body.addView(text("杯序  CupFlow", 25f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(text("饮品配方库 · 店长端", 13f, 0xff71817b.toInt()))
        val importCard = card()
        importCard.addView(text("导入配方文本", 19f).apply { typeface = Typeface.DEFAULT_BOLD })
        importCard.addView(text("按“饮品 / 配料 / 流程”粘贴，自动生成标准步骤。", 13f, 0xff71817b.toInt()))
        importText = EditText(this).apply {
            hint = "饮品：云朵乌龙奶茶\n配料：\n珍珠 1 勺\n少糖\n流程：\n取杯\n加入珍珠\n加入茶底\n核对液位\n扣紧杯盖\n杯贴核验"
            minLines = 8
            setPadding(16, 16, 16, 16)
            setBackground(rounded(0xfff7f6ef.toInt(), 0xffe1e5da.toInt()))
        }
        importCard.addView(importText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        importCard.addView(button("导入并自动配置流程", true) { importRecipeText() })
        status = text("", 13f, 0xff164f3f.toInt()).apply { setPadding(14, 10, 14, 10); background = rounded(0xffe6f3e8.toInt(), 0xffe6f3e8.toInt()) }
        importCard.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })
        body.addView(importCard)
        body.addView(text("已保存的饮品", 19f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8; bottomMargin = 10 })
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(cards)
        return ScrollView(this).apply { addView(body) }
    }

    private fun importRecipeText() {
        val recipe = parseRecipe(importText.text.toString()) ?: return
        val previous = store.load(recipe.drink)
        store.save(recipe.copy(imageUri = previous?.imageUri))
        selected = store.load(recipe.drink)
        setStatus("已导入 ${recipe.drink}：${recipe.ingredients.size} 项配料，${recipe.steps.size} 个流程步骤。")
        renderCards()
    }

    private fun parseRecipe(source: String): DrinkRecipe? {
        var drink = ""
        val ingredients = mutableListOf<String>()
        val steps = mutableListOf<FlowStep>()
        var section = ""
        source.lines().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val normalized = line.replace('：', ':')
            when {
                normalized.startsWith("饮品:") || normalized.startsWith("名称:") -> {
                    drink = normalized.substringAfter(':').trim()
                    section = ""
                }
                normalized.removeSuffix(":") in setOf("配料", "用料") -> section = "ingredients"
                normalized.removeSuffix(":") in setOf("流程", "步骤", "制作流程") -> section = "steps"
                section == "ingredients" -> ingredients += line.removePrefix("-").trim()
                section == "steps" -> {
                    val title = line.removePrefix("-").trim().replace(Regex("^\\d+[.、]\\s*"), "")
                    RecipeStore.stepForTitle(title)?.let(steps::add)
                }
            }
        }
        if (drink.isBlank()) { setStatus("导入失败：请以“饮品：名称”开头。") ; return null }
        if (steps.isEmpty()) { setStatus("导入失败：流程需包含至少一个标准步骤。") ; return null }
        return DrinkRecipe(drink, ingredients, steps)
    }

    private fun selectImage() {
        if (selected == null) { setStatus("请先点击一张配方卡片。") ; return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, imageRequest)
    }

    private fun renderCards() {
        cards.removeAllViews()
        val recipes = store.all()
        if (recipes.isEmpty()) {
            cards.addView(text("暂无配方，请先导入一段配方文本。", 15f))
            return
        }
        recipes.forEach { recipe -> cards.addView(card(recipe)) }
    }

    private fun card(recipe: DrinkRecipe): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            background = rounded(0xfffffdf8.toInt(), 0xffe1e5da.toInt())
            isClickable = true
            setOnClickListener {
                selected = recipe
                setResult(RESULT_OK, Intent().putExtra(EXTRA_DRINK, recipe.drink))
                finish()
            }
            setOnLongClickListener {
                selected = recipe
                setStatus("已选中 ${recipe.drink}，现在可点击“选择图片”。")
                true
            }
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(144)).apply { bottomMargin = 14 }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xffe7f1e8.toInt(), 0xffe7f1e8.toInt())
            if (recipe.imageUri == null) setImageResource(android.R.drawable.ic_menu_gallery) else setImageURI(Uri.parse(recipe.imageUri))
        }
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detail.addView(text(recipe.drink, 20f).apply { typeface = Typeface.DEFAULT_BOLD })
        detail.addView(text("配料 ${recipe.ingredients.size} 项  ·  流程 ${recipe.steps.size} 步", 13f, 0xff71817b.toInt()))
        detail.addView(text(recipe.ingredients.joinToString("、").ifBlank { "未设置配料" }, 13f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8; bottomMargin = 10 })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("使用此配方", true) {
            selected = recipe
            setResult(RESULT_OK, Intent().putExtra(EXTRA_DRINK, recipe.drink))
            finish()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 8 })
        actions.addView(button("选择图片") { selected = recipe; selectImage() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(image)
        row.addView(detail)
        row.addView(actions)
        return row
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 20, 20, 20)
        background = rounded(0xfffffdf8.toInt(), 0xffe1e5da.toInt())
    }
    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply { setColor(fill); setStroke(2, stroke); cornerRadius = dp(18).toFloat() }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setTextColor(if (primary) Color.WHITE else 0xff164f3f.toInt())
        background = rounded(if (primary) 0xff164f3f.toInt() else 0xffeff6e9.toInt(), if (primary) 0xff164f3f.toInt() else 0xffbfc8bd.toInt())
        setOnClickListener { action() }
    }
    private fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(0xff163a27.toInt()); setLineSpacing(4f, 1f) }
    private fun text(value: String, size: Float, color: Int) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setLineSpacing(4f, 1f) }
    private fun setStatus(value: String) { status.text = value }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_DRINK = "drink" }
}
