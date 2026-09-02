package com.cupflow.companion

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class IngredientGrid(
    val id: String,
    val name: String,
    val rows: Int,
    val columns: Int,
    val cells: List<String>,
    val referencePath: String? = null,
)

data class GridVisionContext(
    val expectedIngredient: String,
    val grids: List<IngredientGrid>,
    val referenceImage: String? = null,
)

class IngredientGridStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("cupflow_ingredient_grids", Context.MODE_PRIVATE)

    fun all(): List<IngredientGrid> = runCatching {
        val array = JSONArray(preferences.getString("grids", "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(::fromJson)?.let(::add)
        }
    }.getOrDefault(emptyList())

    fun save(grid: IngredientGrid) {
        val grids = all().filterNot { it.id == grid.id }.toMutableList()
        grids += grid
        preferences.edit().putString("grids", JSONArray(grids.map(::toJson)).toString()).apply()
    }

    fun visionContextFor(stepTitle: String): GridVisionContext? {
        val expected = stepTitle.removePrefix("加入").trim().takeIf { stepTitle.startsWith("加入") && it.isNotBlank() } ?: return null
        val matches = all().filter { grid -> grid.cells.any { cell -> cell.trim() == expected } }
        if (matches.isEmpty()) return null
        val reference = matches.firstNotNullOfOrNull { grid -> grid.referencePath?.let(::readImageDataUrl) }
        return GridVisionContext(expected, matches, reference)
    }

    private fun readImageDataUrl(path: String): String? = runCatching {
        val file = File(path)
        if (!file.isFile || file.length() > 1_000_000) return null
        "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    private fun fromJson(value: JSONObject): IngredientGrid? {
        val rows = value.optInt("rows", 0)
        val columns = value.optInt("columns", 0)
        val cells = value.optJSONArray("cells")?.toStrings().orEmpty()
        val id = value.optString("id").trim()
        if (id.isBlank() || rows !in 1..8 || columns !in 1..8 || cells.size != rows * columns) return null
        return IngredientGrid(id, value.optString("name").ifBlank { "小料格架" }, rows, columns, cells, value.optString("referencePath").ifBlank { null })
    }

    private fun toJson(grid: IngredientGrid) = JSONObject().apply {
        put("id", grid.id)
        put("name", grid.name)
        put("rows", grid.rows)
        put("columns", grid.columns)
        put("cells", JSONArray(grid.cells))
        put("referencePath", grid.referencePath)
    }

    private fun JSONArray.toStrings() = buildList {
        for (index in 0 until length()) add(optString(index).trim())
    }
}
