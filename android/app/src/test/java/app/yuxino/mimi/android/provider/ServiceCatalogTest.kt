package app.yuxino.mimi.android.provider

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ServiceCatalogTest {
    private fun config(provider:ServiceProvider, vararg pairs:Pair<String,String>) = ServiceConfiguration(provider,mapOf("apiKey" to "test-key") + pairs)

    @Test fun catalogHasAllDesktopServicesAndPreservesExistingIds() {
        assertEquals(9,ServiceProvider.entries.size)
        assertEquals(ServiceProvider.DASHSCOPE,ServiceProvider.fromId("dashscope"))
        assertEquals(ServiceProvider.OPENAI,ServiceProvider.fromId("openai"))
        assertFalse(ServiceProvider.TENCENT.configured(mapOf("apiKey" to "irrelevant")))
        assertFalse(ServiceProvider.AZURE.configured(mapOf("apiKey" to "only-a-key")))
        assertFalse(ServiceProvider.CUSTOM_OPENAI.configured(mapOf("endpoint" to "wss://service.example/ws", "model" to "asr-model")))
        assertTrue(ServiceProvider.CUSTOM_OPENAI.configured(mapOf("endpoint" to "wss://service.example/ws", "model" to "asr-model", "apiKey" to "test-key")))
    }
    @Test fun switchingFromAutoToExplicitProviderChoosesSupportedDifferentLanguages() {
        for(p in ServiceProvider.entries) {
            // Custom recognition only accepts original captions without an
            // independent text translator, mirroring the shared runtime contract.
            val (source,target)=p.normalize("auto", if (p == ServiceProvider.CUSTOM_OPENAI) "original" else "zh")
            assertTrue(source in p.sources); assertTrue(target in p.targets); assertTrue(p.supportsPair(source, target))
            val pair=p.normalize("ko","ko"); assertTrue(pair.first in p.sources); assertTrue(pair.second in p.targets)
        }
        assertEquals("auto" to "zh",ServiceProvider.OPENAI.normalize("en","zh"))
        assertTrue(ServiceProvider.VOLCANO.sources.contains("ko"))
        assertFalse(ServiceProvider.VOLCANO.supportsPair("ko", "ja"))
    }
    @Test fun genericTextTargetsStaySeparateFromSpeechAndStrictTranslationCatalogs() {
        val cases = JSONObject(requireNotNull(javaClass.getResourceAsStream("/translation-contracts.json"))
            .bufferedReader().use { it.readText() }).getJSONArray("requests")
        val expectedTargets = (0 until cases.length()).map(cases::getJSONObject)
            .filter { it.getString("provider") == "openaiCompatible" && !it.isNull("expected") }
            .map { it.getString("target") }.toSet() + "original"
        for (route in listOf(TextTranslationProvider.OPENAI_COMPATIBLE, TextTranslationProvider.CHAT_MOCK)) {
            val targets = ServiceProvider.DASHSCOPE.targetsForTranslation(route)
            assertEquals(expectedTargets, targets.toSet())
            assertTrue(targets.contains("original"))
            for (target in targets) assertEquals("auto" to target, ServiceProvider.DASHSCOPE.normalize("auto", target, route))
            assertEquals("ja" to "zh_tw", ServiceProvider.DASHSCOPE.normalize("ja", "zh_tw", route))
            // The runtime shares PC's Audio3 source catalog and same-language behavior.
            assertEquals("fr" to "fr", ServiceProvider.DASHSCOPE.normalize("fr", "fr", route))
            for (provider in ServiceProvider.entries.filter { it != ServiceProvider.DASHSCOPE && it != ServiceProvider.CUSTOM_OPENAI }) {
                assertEquals(provider.targets, provider.targetsForTranslation(route))
            }
        }
        assertTrue(ServiceProvider.DASHSCOPE.targetsForTranslation(TextTranslationProvider.BUILTIN).contains("original"))
        assertFalse(ServiceProvider.DASHSCOPE.targetsForTranslation(TextTranslationProvider.BUILTIN).contains("yue"))
        assertEquals("auto" to "fr", ServiceProvider.DASHSCOPE.normalize("auto", "fr"))

    }

    @Test fun customRecognitionKeepsItsOwnCredentialShapeAndOriginalOnlyDefaults() {
        val values = mapOf("endpoint" to " wss://service.example/v1/realtime ", "model" to " asr-model ", "apiKey" to " custom-key ")
        val speech = ServiceConfiguration(ServiceProvider.CUSTOM_OPENAI, values)
        assertTrue(speech.usesIndependentTextTranslation)
        val credentials = speech.runtimeCredentials()
        assertEquals("customSpeech", credentials.getString("kind"))
        assertEquals("wss://service.example/v1/realtime", credentials.getString("endpoint"))
        assertEquals("asr-model", credentials.getString("model"))
        assertEquals("custom-key", credentials.getString("apiKey"))
        // Without a translator the captions stay original; any configured text route is independent.
        assertEquals(listOf("original"), ServiceProvider.CUSTOM_OPENAI.targets)
        // The public contract: no translator means original-only captions.
        assertTrue(speech.originalOnly(TextTranslationProvider.NONE))
        assertFalse(speech.originalOnly(TextTranslationProvider.OPENAI_COMPATIBLE))
        assertEquals("simultaneous", speech.speechTranslationMode)
    }
    @Test fun credentialsDoNotLeakThroughObjectDiagnostics() {
        assertFalse(config(ServiceProvider.GEMINI,"apiKey" to "do-not-print").toString().contains("do-not-print"))
    }
}
