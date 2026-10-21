package com.mzstd.hxmyproxy

import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Run each phase in a separate process; the caller force-stops the owned emulator app between phases. */
class RuleImportRestartProbe {
    @Test fun persistedImportSurvivesProcessDeath() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("restartPhase")
        assumeTrue(phase in setOf("stage", "commit", "receipt"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = File(context.filesDir, "rule-import-restart-probe.json")
        val repo = SettingsRepository(context)
        val io = RuleImportRepository(context, RuleLibraryRepository(context, repo))
        val pid = android.os.Process.myPid()
        if (phase == "stage") {
            check(!state.exists()) { "Unfinished probe must be recovered first" }
            val job = io.read("restart.txt", { (1..600).joinToString("\n") { "restart-$it.example.org" }.byteInputStream() })
            assertEquals(RuleImportRepository.Phase.READY, job.phase)
            state.writeText(JSONObject().put("job", job.id).put("stagePid", pid).toString())
        } else {
            val saved = JSONObject(state.readText()); assertNotEquals(saved.getInt("stagePid"), pid)
            val job = io.recover().single { it.id == saved.getString("job") }
            if (phase == "commit") {
                assertEquals(RuleImportRepository.Phase.READY, job.phase); assertEquals(600, job.valid)
                val committed = io.commit(job, RuleImportRepository.Destination(name = "Restart probe"))
                assertEquals(RuleImportRepository.Phase.DONE, committed.phase)
                // Emulate death after atomic settings commit but before journal phase persistence.
                val journal = io.store.jobs().map(::JSONObject).single { it.getString("id") == job.id }
                journal.put("phase", "SAVING").put("saved", 0).put("undoId", JSONObject.NULL)
                io.store.saveJob(job.id, journal.toString())
                state.writeText(saved.put("commitPid", pid).toString())
            } else {
                assertNotEquals(saved.getInt("commitPid"), pid)
                assertEquals(RuleImportRepository.Phase.DONE, job.phase); assertEquals(600, job.saved)
                assertEquals(1, repo.settings.first().userRuleSets.count { it.id == job.id })
                val repeat = io.commit(job, RuleImportRepository.Destination(name = "Must not duplicate"))
                assertEquals(600, repeat.saved)
                assertTrue(repo.revertLibraryChange(job.undoId!!))
                assertTrue(repo.settings.first().userRuleSets.none { it.id == job.id })
                assertTrue(state.delete())
            }
        }
    }
}
