package com.ai.assistance.operit.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 issue #976：同一模型配置里混放纯文本模型和视觉模型时，
 * 「模型支持识图」不能再按配置级一刀切，必须落到实际选中的那个模型上。
 */
class ModelMediaCapabilityTest {

    @Test
    fun mixedConfig_textChatModel_doesNotClaimVision() {
        val config = mixedVisionConfig()

        // CHAT 绑定到纯文本模型（索引1）
        assertFalse(config.supportsDirectImageProcessing(TEXT_MODEL_INDEX))
    }

    @Test
    fun mixedConfig_visionModel_stillProcessesImagesDirectly() {
        val config = mixedVisionConfig()

        // IMAGE_RECOGNITION 绑定到视觉模型（索引3）
        assertTrue(config.supportsDirectImageProcessing(VISION_MODEL_INDEX))
    }

    @Test
    fun singleModelConfig_isUnaffected() {
        val config =
            ModelConfigData(
                id = "qwenvl",
                name = "Qwenvl",
                modelName = "qwen3-vl-flash",
                enableDirectImageProcessing = true
            )

        assertTrue(config.supportsDirectImageProcessing())
        assertTrue(config.supportsDirectImageProcessing(0))
    }

    @Test
    fun multiModelConfigWithoutDeclaration_keepsLegacyBehaviour() {
        val config =
            ModelConfigData(
                id = "legacy",
                name = "Legacy",
                modelName = "gpt-4o,gpt-4o-mini",
                enableDirectImageProcessing = true
            )

        assertTrue(config.supportsDirectImageProcessing(0))
        assertTrue(config.supportsDirectImageProcessing(1))
    }

    @Test
    fun configSwitchOff_overridesPerModelDeclaration() {
        val config = mixedVisionConfig().copy(enableDirectImageProcessing = false)

        assertFalse(config.supportsDirectImageProcessing(VISION_MODEL_INDEX))
    }

    @Test
    fun outOfRangeIndex_fallsBackToFirstModel() {
        val config = mixedVisionConfig()

        // 越界索引与索引0一致：第一个模型是纯文本模型
        assertFalse(config.supportsDirectImageProcessing(99))
        assertFalse(config.supportsDirectImageProcessing(-1))
    }

    @Test
    fun declarationMatchesModelNameCaseInsensitively() {
        val config =
            ModelConfigData(
                id = "case",
                name = "Case",
                modelName = "Qwen3-VL-Flash,deepseek-v4-pro",
                enableDirectImageProcessing = true,
                directImageModels = "qwen3-vl-flash"
            )

        assertTrue(config.supportsDirectImageProcessing(0))
        assertFalse(config.supportsDirectImageProcessing(1))
    }

    @Test
    fun singleModelWithStaleDeclaration_usesVisibleSwitch() {
        val config =
            ModelConfigData(
                id = "stale",
                name = "Stale",
                modelName = "deepseek-v4-pro",
                enableDirectImageProcessing = true,
                directImageModels = "qwen3-vl-flash"
            )

        assertTrue(config.supportsDirectImageProcessing(0))
    }

    @Test
    fun audioAndVideoDeclarationsAreIndependent() {
        val config =
            ModelConfigData(
                id = "media",
                name = "Media",
                modelName = "text-only,omni",
                enableDirectAudioProcessing = true,
                enableDirectVideoProcessing = true,
                directAudioModels = "omni",
                directVideoModels = "omni"
            )

        assertFalse(config.supportsDirectAudioProcessing(0))
        assertFalse(config.supportsDirectVideoProcessing(0))
        assertTrue(config.supportsDirectAudioProcessing(1))
        assertTrue(config.supportsDirectVideoProcessing(1))
        // 未开启的图片能力不受影响
        assertFalse(config.supportsDirectImageProcessing(1))
    }

    @Test
    fun retainCapableModels_emptyDeclarationMeansEveryModel() {
        assertEquals(listOf("a", "b"), retainCapableModels("a,b", ""))
    }

    @Test
    fun retainCapableModels_followsModelListOrderAndDropsUnknown() {
        assertEquals(listOf("a", "c"), retainCapableModels("a,b,c", "c, removed ,a"))
    }

    @Test
    fun normalizeCapableModels_collapsesFullCoverageToEmpty() {
        assertEquals("", normalizeCapableModels("a,b", "b,a"))
        assertEquals("", normalizeCapableModels("a,b", ""))
    }

    @Test
    fun normalizeCapableModels_keepsSubsetInModelListOrder() {
        assertEquals("a,c", normalizeCapableModels("a,b,c", "c,a"))
    }

    @Test
    fun addingModel_preservesExplicitSubsetForAllMedia() {
        val changed = mediaConfig("text,omni", "omni")
            .withModelNameAndNormalizedMediaCapabilities("text,omni,new")

        assertDeclarations(changed, "omni")
        assertMediaSupport(changed, listOf(false, true, false))
    }

    @Test
    fun addingModel_toPreviouslyAllSelected_doesNotGrantNewModelCapability() {
        val changed = mediaConfig("first,second", "")
            .withModelNameAndNormalizedMediaCapabilities("first,second,new")

        assertDeclarations(changed, "first,second")
        assertMediaSupport(changed, listOf(true, true, false))
    }

    @Test
    fun deletingModels_keepsOnlySurvivingDeclarationsInNewOrder() {
        val changed = mediaConfig("text,old,kept,last", "old,kept,last")
            .withModelNameAndNormalizedMediaCapabilities("LAST,text,kept")

        assertDeclarations(changed, "LAST,kept")
        assertMediaSupport(changed, listOf(true, false, true))
    }

    @Test
    fun deletingEveryCapableModel_disablesAllMediaWithoutGrantingOthers() {
        val changed = mediaConfig("text,omni,other", "omni")
            .withModelNameAndNormalizedMediaCapabilities("text,other")

        assertDeclarations(changed, "")
        assertFalse(changed.enableDirectImageProcessing)
        assertFalse(changed.enableDirectAudioProcessing)
        assertFalse(changed.enableDirectVideoProcessing)
        assertMediaSupport(changed, listOf(false, false))
    }

    @Test
    fun shrinkingToOneModel_clearsHiddenDeclarationsAndPreservesSwitches() {
        for (newModel in listOf("text", "omni", "vision-new")) {
            val changed = mediaConfig("text,omni", "omni")
                .withModelNameAndNormalizedMediaCapabilities(newModel)

            assertDeclarations(changed, "")
            assertMediaSupport(changed, listOf(true))
        }
    }

    @Test
    fun existingStaleSingleModelConfig_normalizesOnLoadAndCanBeToggled() {
        val stale = mediaConfig("vision-new", "vision-old")
        assertMediaSupport(stale, listOf(true))
        val normalized = stale.withNormalizedMediaCapabilities()
        assertDeclarations(normalized, "")
        assertMediaSupport(normalized, listOf(true))

        val disabled = normalized.copy(
            enableDirectImageProcessing = false,
            enableDirectAudioProcessing = false,
            enableDirectVideoProcessing = false
        ).withNormalizedMediaCapabilities()
        assertMediaSupport(disabled, listOf(false))
        assertMediaSupport(disabled.copy(
            enableDirectImageProcessing = true,
            enableDirectAudioProcessing = true,
            enableDirectVideoProcessing = true
        ).withNormalizedMediaCapabilities(), listOf(true))
    }

    @Test
    fun staleMultiModelDeclarations_areRemovedAndCannotReturnWhenReadded() {
        val normalized = mediaConfig("text,other", "removed")
            .withNormalizedMediaCapabilities()
        assertDeclarations(normalized, "")
        assertMediaSupport(normalized, listOf(false, false))
        assertMediaSupport(normalized.withModelNameAndNormalizedMediaCapabilities(
            "text,other,removed"
        ), listOf(false, false, false))
    }

    @Test
    fun normalization_keepsMediaDeclarationsIndependentAndIsIdempotent() {
        val normalized = mediaConfig("text,image,audio,video", "image")
            .copy(directAudioModels = "audio", directVideoModels = "video")
            .withModelNameAndNormalizedMediaCapabilities("text,image,video")

        assertEquals("image", normalized.directImageModels)
        assertEquals("", normalized.directAudioModels)
        assertEquals("video", normalized.directVideoModels)
        assertTrue(normalized.supportsDirectImageProcessing(1))
        assertFalse(normalized.enableDirectAudioProcessing)
        assertTrue(normalized.supportsDirectVideoProcessing(2))
        assertEquals(normalized, normalized.withNormalizedMediaCapabilities())
    }

    private fun mediaConfig(models: String, declaration: String) = ModelConfigData(
        id = "media-regression", name = "Media regression", modelName = models,
        enableDirectImageProcessing = true, enableDirectAudioProcessing = true,
        enableDirectVideoProcessing = true, directImageModels = declaration,
        directAudioModels = declaration, directVideoModels = declaration
    )

    private fun assertDeclarations(config: ModelConfigData, expected: String) {
        assertEquals(expected, config.directImageModels)
        assertEquals(expected, config.directAudioModels)
        assertEquals(expected, config.directVideoModels)
    }

    private fun assertMediaSupport(config: ModelConfigData, expected: List<Boolean>) {
        expected.forEachIndexed { index, supported ->
            assertEquals("image at $index", supported, config.supportsDirectImageProcessing(index))
            assertEquals("audio at $index", supported, config.supportsDirectAudioProcessing(index))
            assertEquals("video at $index", supported, config.supportsDirectVideoProcessing(index))
        }
    }

    private fun mixedVisionConfig(): ModelConfigData =
        ModelConfigData(
            id = "qwen",
            name = "Qwen",
            modelName = "deepseek-v4-flash-0731,deepseek-v4-pro-0813,qwen-max,qwen3-vl-flash",
            enableDirectImageProcessing = true,
            directImageModels = "qwen3-vl-flash"
        )

    private companion object {
        const val TEXT_MODEL_INDEX = 1
        const val VISION_MODEL_INDEX = 3
    }
}
