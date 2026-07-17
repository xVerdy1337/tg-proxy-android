package com.tgwsproxy.vpn

/**
 * Curated, user-facing ByeDPI strategies.
 *
 * Keep these commands in one place: the VPN defaults, the preset picker and the real native
 * auto-tuner must all test and run the same strings. Commands are candidates, not guarantees —
 * the provider's DPI behaviour can change, so the auto-tuner still verifies them end-to-end.
 */
enum class ByedpiPresetGroup(val title: String) {
    LIGHT("Лёгкие"),
    BALANCED("Стабильные"),
    AGGRESSIVE("Агрессивные"),
    EXPERIMENTAL("Экспериментальные"),
    DIAGNOSTIC("Диагностика"),
}

data class ByedpiPreset(
    val id: String,
    val label: String,
    val description: String,
    val command: String,
    val group: ByedpiPresetGroup,
    val diagnostic: Boolean = false,
)

object ByedpiPresetCatalog {

    /**
     * The order is intentional: cheaper and more common strategies run first during auto-tune.
     * Keep the list deterministic so progress and support reports are reproducible.
     */
    val presets: List<ByedpiPreset> = listOf(
        ByedpiPreset(
            id = DesyncVpnService.PRESET_AUTO,
            label = "Авто — каскад",
            description = "Сильный каскад disorder + split. Основной универсальный вариант.",
            command = "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = DesyncVpnService.PRESET_SPLIT,
            label = "SNI split",
            description = "Многоточечный split без FAKE. Обычно легче для батареи и задержки.",
            command = "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = DesyncVpnService.PRESET_TLSREC,
            label = "TLS-record + FAKE",
            description = "Split + TLS-record + низкий TTL. Запасной вариант для жёсткого DPI.",
            command = "-d1 -s1+s -r1+s -f-1 -t8 -a1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "split-minimal",
            label = "Минимальный split",
            description = "Одна точка разреза у SNI — минимальная нагрузка и быстрый тест.",
            command = "-s1+s",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = "tlsrec-minimal",
            label = "Минимальный TLS-record",
            description = "Только рефрагментация TLS-записи у SNI.",
            command = "-r1+s",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = "disorder-minimal",
            label = "Минимальный disorder",
            description = "Обратный порядок сегментов у SNI без каскада.",
            command = "-d1+s",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = "split-triple",
            label = "Split: 3 точки",
            description = "Короткий каскад split для сетей, где длинные цепочки ломают соединение.",
            command = "-s1+s -s3+s -s6+s",
            group = ByedpiPresetGroup.LIGHT,
        ),
        ByedpiPreset(
            id = "disorder-split",
            label = "Disorder + split",
            description = "Простая комбинация двух базовых методов.",
            command = "-d1 -s1+s",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "split-tlsrec",
            label = "Split + TLS-record",
            description = "Два способа спрятать границу ClientHello без FAKE-пакета.",
            command = "-d1 -s1+s -r1+s",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "mixed-short",
            label = "Короткий микс",
            description = "Disorder и split на нескольких ранних позициях.",
            command = "-d1 -s1+s -d3+s -s6+s",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "mixed-step",
            label = "Каскад с шагом",
            description = "Разреженный каскад для DPI, который собирает первые сегменты.",
            command = "-d1 -s1+s -s3+s -d6+s -s12+s -a1",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "tlsrec-double",
            label = "Двойной TLS-record",
            description = "TLS-record и split на разных позициях.",
            command = "-r1+s -s25+s -a1 -At,r,s -s50 -r1+s -s50+s -a1",
            group = ByedpiPresetGroup.BALANCED,
        ),
        ByedpiPreset(
            id = "auto-oob",
            label = "OOB + авто",
            description = "Out-of-band сегмент с автоматическими fallback-секциями.",
            command = "-o1 -a1 -At,r,s -d1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "disorder-oob",
            label = "Disorder + OOB",
            description = "Обратный порядок и OOB для сетей с сильным анализом TCP.",
            command = "-d6+s -q4+hm -o2 -a1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "fake-ttl6",
            label = "FAKE TTL 6 + split",
            description = "Низкий TTL у FAKE-пакета и разрезание настоящего ClientHello.",
            command = "-f-1 -t6 -s1+s -a1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "fake-ttl8",
            label = "FAKE TTL 8 + TLS-record",
            description = "Более агрессивный FAKE с TLS-record.",
            command = "-f-1 -t8 -s1+s -r1+s -a1",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "long-cascade",
            label = "Длинный каскад",
            description = "Много точек disorder + split для жёстких профилей DPI.",
            command = "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s",
            group = ByedpiPresetGroup.AGGRESSIVE,
        ),
        ByedpiPreset(
            id = "oob-disorder",
            label = "Disoob + OOB",
            description = "Экспериментальная комбинация обратного OOB и обычного OOB.",
            command = "-q4+hm -o2 -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = "fake-random-tls",
            label = "FAKE с random TLS",
            description = "Меняет параметры fake ClientHello; иногда помогает против сигнатур.",
            command = "-f-1 -t8 -Qrand -s1+s -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = "fake-original-tls",
            label = "FAKE с original TLS",
            description = "Сохраняет оригинальные параметры fake ClientHello.",
            command = "-f-1 -t8 -Qorig -s1+s -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = "fake-google-sni",
            label = "FAKE: Google SNI",
            description = "Подмена SNI только в decoy-пакете. Проверяй на своей сети.",
            command = "-f-1 -t8 -nwww.google.com -s1+s -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = "fake-microsoft-sni",
            label = "FAKE: Microsoft SNI",
            description = "Альтернативная подмена SNI в decoy-пакете.",
            command = "-f-1 -t6 -nwww.microsoft.com -d1+s -a1",
            group = ByedpiPresetGroup.EXPERIMENTAL,
        ),
        ByedpiPreset(
            id = DesyncVpnService.PRESET_OFF,
            label = "Без обхода",
            description = "Обычный relay без desync — только для диагностики трубы.",
            command = "",
            group = ByedpiPresetGroup.DIAGNOSTIC,
            diagnostic = true,
        ),
    )

    val autotunePresets: List<ByedpiPreset> = presets.filterNot { it.diagnostic }

    fun byId(id: String): ByedpiPreset? = presets.firstOrNull { it.id == id }

    fun byCommand(command: String): ByedpiPreset? =
        presets.firstOrNull { it.command == command.trim() && it.command.isNotEmpty() }

    fun commandFor(id: String): String =
        byId(id)?.command ?: byId(DesyncVpnService.PRESET_AUTO)?.command.orEmpty()
}
