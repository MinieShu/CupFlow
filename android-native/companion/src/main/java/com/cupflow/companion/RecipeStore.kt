package com.cupflow.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FlowStep(val title: String, val event: String)
data class DrinkRecipe(
    val drink: String,
    val ingredients: List<String>,
    val steps: List<FlowStep>,
    val imageUri: String? = null,
)

class RecipeStore(context: Context) {
    private val preferences = context.getSharedPreferences("cupflow_recipes", Context.MODE_PRIVATE)

    init {
        val savedDefault = load(defaultGuMingMilkTea.drink)
        if (savedDefault == null || savedDefault.steps.map { it.title } == legacyDefaultStepTitles) save(defaultGuMingMilkTea)
        showcaseRecipes.forEach { recipe -> if (load(recipe.drink) == null) save(recipe) }
    }

    fun load(drink: String): DrinkRecipe? = runCatching {
        val all = JSONObject(preferences.getString("recipes", "{}") ?: "{}")
        val recipe = all.optJSONObject(key(drink)) ?: return null
        val ingredients = recipe.optJSONArray("ingredients").toStrings()
        val steps = recipe.optJSONArray("steps").toStrings().mapNotNull(::stepForTitle)
        if (steps.isEmpty()) null else DrinkRecipe(drink, ingredients, steps, recipe.optString("imageUri").ifBlank { null })
    }.getOrNull()

    fun all(): List<DrinkRecipe> = runCatching {
        val all = JSONObject(preferences.getString("recipes", "{}") ?: "{}")
        all.keys().asSequence().mapNotNull { key ->
            val recipe = all.optJSONObject(key) ?: return@mapNotNull null
            val name = recipe.optString("drink").ifBlank { key }
            val ingredients = recipe.optJSONArray("ingredients").toStrings()
            val steps = recipe.optJSONArray("steps").toStrings().mapNotNull(::stepForTitle)
            steps.takeIf { it.isNotEmpty() }?.let { DrinkRecipe(name, ingredients, it, recipe.optString("imageUri").ifBlank { null }) }
        }.sortedBy { it.drink }.toList()
    }.getOrDefault(emptyList())

    fun save(recipe: DrinkRecipe) {
        val all = JSONObject(preferences.getString("recipes", "{}") ?: "{}")
        all.put(key(recipe.drink), JSONObject().apply {
            put("drink", recipe.drink)
            put("ingredients", JSONArray(recipe.ingredients))
            put("steps", JSONArray(recipe.steps.map { it.title }))
            put("imageUri", recipe.imageUri)
        })
        preferences.edit().putString("recipes", all.toString()).apply()
    }

    private fun JSONArray?.toStrings(): List<String> = if (this == null) emptyList() else buildList {
        for (index in 0 until length()) optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
    }

    private fun key(drink: String) = drink.trim().lowercase()

    companion object {
        val supportedSteps = listOf(
            FlowStep("取量杯", "measureCup"),
            FlowStep("加入奶", "milk"),
            FlowStep("加入茶", "tea"),
            FlowStep("取饮品杯", "cup"),
            FlowStep("加入珍珠", "pearls"),
            FlowStep("加入混合后的奶茶", "mixedTea"),
            FlowStep("盖盖", "seal"),
            FlowStep("检查杯贴", "label"),
            FlowStep("取杯", "cup"),
            FlowStep("加入茶底", "tea"),
            FlowStep("核对液位", "measure"),
            FlowStep("扣紧杯盖", "seal"),
            FlowStep("杯贴核验", "label"),
        )

        val defaultGuMingMilkTea = DrinkRecipe(
            drink = "古茗奶茶",
            ingredients = listOf("珍珠"),
            steps = listOf(
                FlowStep("取量杯", "measureCup"),
                FlowStep("加入奶", "milk"),
                FlowStep("加入茶", "tea"),
                FlowStep("取饮品杯", "cup"),
                FlowStep("加入珍珠", "pearls"),
                FlowStep("加入混合后的奶茶", "mixedTea"),
                FlowStep("盖盖", "seal"),
                FlowStep("检查杯贴", "label"),
            ),
        )

        val showcaseRecipes = listOf(
            defaultGuMingMilkTea.copy(drink = "珍珠奶茶"),
            defaultGuMingMilkTea.copy(
                drink = "椰果奶茶",
                ingredients = listOf("椰果"),
                steps = standardMilkTeaSteps(listOf("椰果")),
            ),
            defaultGuMingMilkTea.copy(
                drink = "红豆布丁奶茶",
                ingredients = listOf("红豆", "布丁"),
                steps = standardMilkTeaSteps(listOf("红豆", "布丁")),
            ),
            defaultGuMingMilkTea.copy(
                drink = "奶盖多肉奶茶",
                ingredients = listOf("多肉", "奶盖"),
                steps = standardMilkTeaSteps(listOf("多肉", "奶盖")),
            ),
        )

        fun stepForTitle(title: String): FlowStep? = supportedSteps.firstOrNull { it.title == title }
            ?: title.takeIf { it.startsWith("加入") && it.removePrefix("加入").trim().isNotBlank() }?.let { FlowStep(it, "topping") }

        fun recipeFor(order: CupOrder, savedRecipe: DrinkRecipe? = null): DrinkRecipe {
            val base = savedRecipe ?: defaultFor(order)
            val existing = base.steps.map { it.title.removePrefix("加入").trim() }.toSet()
            val additions = order.options.mapNotNull(::additionName).filterNot(existing::contains)
            if (additions.isEmpty()) return base
            val bottom = additions.filterNot(::isTopAddition)
            val top = additions.filter(::isTopAddition)
            val steps = base.steps.toMutableList()
            var bottomIndex = (steps.indexOfFirst { it.title == "取饮品杯" } + 1).coerceAtLeast(0)
            bottom.forEach { addition -> steps.add(bottomIndex++, stepForTitle("加入$addition")!!) }
            val lidIndex = steps.indexOfFirst { it.title == "盖盖" || it.title == "扣紧杯盖" }
            val measureIndex = steps.indexOfFirst { it.title == "核对液位" }
            var topIndex = if (lidIndex >= 0) lidIndex else if (measureIndex >= 0) measureIndex + 1 else steps.size
            top.forEach { addition -> steps.add(topIndex++, stepForTitle("加入$addition")!!) }
            return base.copy(steps = steps)
        }

        fun defaultFor(order: CupOrder) = defaultGuMingMilkTea.copy(drink = order.drink, ingredients = order.options)

        private fun standardMilkTeaSteps(additions: List<String>): List<FlowStep> {
            val bottom = additions.filterNot(::isTopAddition).map { addition -> stepForTitle("加入$addition")!! }
            val top = additions.filter(::isTopAddition).map { addition -> stepForTitle("加入$addition")!! }
            return listOf(
            FlowStep("取量杯", "measureCup"),
            FlowStep("加入奶", "milk"),
            FlowStep("加入茶", "tea"),
            FlowStep("取饮品杯", "cup"),
            ) + bottom + listOf(
            FlowStep("加入混合后的奶茶", "mixedTea"),
            ) + top + listOf(
            FlowStep("盖盖", "seal"),
            FlowStep("检查杯贴", "label"),
        )
        }

        private fun additionName(option: String): String? {
            val value = option.trim()
            val known = bottomAdditions + topAdditions
            return when {
                value.startsWith("加") -> value.removePrefix("加").trim().takeIf { it.isNotBlank() }
                known.any(value::contains) -> known.first(value::contains)
                else -> null
            }
        }

        private fun isTopAddition(name: String) = topAdditions.any(name::contains)
        private val bottomAdditions = setOf("珍珠", "椰果", "仙草", "布丁", "芋圆", "红豆", "西米", "多肉", "西柚", "冻冻")
        private val topAdditions = setOf("芝士", "奶盖", "奶油")
        private val legacyDefaultStepTitles = listOf("加入珍珠", "加入奶", "加入茶", "盖盖")
    }
}
