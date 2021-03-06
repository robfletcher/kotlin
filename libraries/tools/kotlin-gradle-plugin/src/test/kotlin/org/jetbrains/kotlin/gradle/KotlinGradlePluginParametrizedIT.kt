package org.jetbrains.kotlin.gradle

import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@Ignore
@RunWith(Parameterized::class)
class KotlinGradlePluginJpsParametrizedIT : BaseIncrementalGradleIT() {

    @Parameterized.Parameter
    @JvmField
    var relativePath: String = ""

    @Test
    fun testFromJps() {
        JpsTestProject(jpsResourcesPath, relativePath).performAndAssertBuildStages(weakTesting = true)
    }

    override fun defaultBuildOptions(): BuildOptions = BuildOptions(withDaemon = true)

    companion object {

        private val jpsResourcesPath = File("../../../jps-plugin/testData/incremental")
        private val ignoredDirs = setOf(File(jpsResourcesPath, "cacheVersionChanged"),
                                        File(jpsResourcesPath, "changeIncrementalOption"),
                                        File(jpsResourcesPath, "custom"),
                                        File(jpsResourcesPath, "lookupTracker"))

        @Suppress("unused")
        @Parameterized.Parameters(name = "{index}: {0}")
        @JvmStatic
        fun data(): List<Array<String>> =
                jpsResourcesPath.walk()
                        .onEnter { it !in ignoredDirs }
                        .filter { it.isDirectory && isJpsTestProject(it) }
                        .map { arrayOf(it.toRelativeString(jpsResourcesPath)) }
                        .toList()
    }
}

