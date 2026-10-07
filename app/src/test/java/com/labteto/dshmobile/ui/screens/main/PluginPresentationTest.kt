package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.PluginOperationState
import com.labteto.dshmobile.core.wire.dto.PluginChangeResult
import org.junit.Assert.*
import org.junit.Test

class PluginPresentationTest {
    @Test fun `approval captures operation package registry and scripts independently of form`() {
        val state = PluginOperationState(host = "host", requestId = "request", subject = "@scope/first@1", registry = "https://registry.example",
            result = PluginChangeResult(false, "failed", "install", "@scope/first", pendingBuilds = listOf("native-addon@2")))
        val approval = state.approvalSubject()!!
        val editedForm = PluginInstallSubject("other@9", "https://other.example")
        assertNotEquals(editedForm.target, approval.target)
        assertEquals("@scope/first@1", approval.target)
        assertEquals("https://registry.example", approval.registry)
        assertEquals(listOf("native-addon@2"), approval.approvedBuilds)
        assertEquals("@scope/first@1", state.copy(busy = false, unknown = true).displaySubject())
    }
    @Test fun `legacy unknown operation never exposes UUID as package or invents approval target`() {
        val state = PluginOperationState(host = "host", requestId = "opaque-id", unknown = true)
        assertNull(state.displaySubject())
        assertNull(state.approvalSubject())
    }
    @Test fun `successful noninstall mutation identifies its returned package`() {
        val state = PluginOperationState(result = PluginChangeResult(true, "applied", "enable", "package"))
        assertEquals("package", state.displaySubject())
        assertNull(state.approvalSubject())
    }
}
