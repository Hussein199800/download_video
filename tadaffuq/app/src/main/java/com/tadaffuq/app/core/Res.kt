package com.tadaffuq.app.core

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale

/**
 * نص مُنسّق بأرقام لاتينية معزولة اتجاهياً، أياً كانت لغة النظام
 * (String.format بالعربية يكتب الأرقام الهندية، وصاحب المشروع يعتمد اللاتينية).
 */
private val intSpec = Regex("%(\\d+\\$)?d")

fun formatLatin(template: String, args: Array<out Any?>): String {
    val t = template.replace(intSpec) { "%" + (it.groupValues[1]) + "s" }
    val a = args.map { if (it is Number) Fmt.iso(it.toString()) else it }.toTypedArray()
    return String.format(Locale.ROOT, t, *a)
}

@Composable
fun sr(@StringRes id: Int, vararg args: Any?): String = formatLatin(stringResource(id), args)

fun Context.sr(@StringRes id: Int, vararg args: Any?): String = formatLatin(getString(id), args)
