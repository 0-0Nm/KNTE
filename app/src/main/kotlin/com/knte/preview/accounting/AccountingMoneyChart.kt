package com.knte.preview.accounting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Narrow, paper-like Canvas chart. Values stay Long until the final coordinate conversion. */
@Composable
fun AccountingMoneyChart(model: AccountingChartModel, money: (Long) -> String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("真实收支折线", style = MaterialTheme.typography.titleMedium)
        Text(
            if (model.scope.isEmpty()) "范围：全部月份；仅绘制有有效流水的日期，缺失日期不连线。"
            else "范围：${model.scope}；按日汇总，零值日期保留在月范围内。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("收入", MaterialTheme.colorScheme.primary, solid = true)
            Legend("支出", MaterialTheme.colorScheme.error, solid = false)
        }
        Text(model.statusLabel, style = MaterialTheme.typography.bodySmall)
        if (model.status == AccountingChartStatus.UNAVAILABLE || model.status == AccountingChartStatus.EMPTY) return@Column
        val description = buildString {
            append("收支折线图，")
            append("收入合计 ${money(model.incomeTotal)}，支出合计 ${money(model.expenseTotal)}。")
            model.points.forEach { append("${it.date} 收入 ${money(it.income)} 支出 ${money(it.expense)}；") }
        }
        Canvas(
            modifier = Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = description },
        ) {
            val left = 12f
            val right = size.width - 12f
            val top = 12f
            val bottom = size.height - 18f
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            val max = model.maxValue.toDouble().coerceAtLeast(1.0)
            fun x(index: Int): Float = if (model.points.size == 1) left + width / 2f else left + width * index / (model.points.size - 1).toFloat()
            fun y(value: Long): Float = bottom - (value.toDouble() / max).toFloat().coerceIn(0f, 1f) * height
            drawLine(MaterialTheme.colorScheme.outlineVariant, Offset(left, bottom), Offset(right, bottom), 1f)
            drawLine(MaterialTheme.colorScheme.outlineVariant, Offset(left, top), Offset(left, bottom), 1f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            drawLine(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f), Offset(left, top + height / 2), Offset(right, top + height / 2), 1f, pathEffect = dash)
            model.points.forEachIndexed { index, point ->
                val xi = x(index)
                val yi = y(point.income)
                val ye = y(point.expense)
                if (model.joins(index)) {
                    val previous = model.points[index - 1]
                    drawLine(MaterialTheme.colorScheme.primary, Offset(x(index - 1), y(previous.income)), Offset(xi, yi), 3f, cap = StrokeCap.Round)
                    drawLine(MaterialTheme.colorScheme.error, Offset(x(index - 1), y(previous.expense)), Offset(xi, ye), 3f, cap = StrokeCap.Round)
                }
                drawCircle(MaterialTheme.colorScheme.primary, 4f, Offset(xi, yi))
                drawCircle(MaterialTheme.colorScheme.error, 4f, Offset(xi, ye), style = Stroke(width = 2f))
            }
        }
        if (model.points.isNotEmpty()) {
            Text(
                "首日 ${model.points.first().date} · 末日 ${model.points.last().date} · 最大日值 ${money(model.maxValue)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Legend(label: String, color: androidx.compose.ui.graphics.Color, solid: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.padding(top = 5.dp).height(10.dp).fillMaxWidth(.08f)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3f, pathEffect = if (solid) null else PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
