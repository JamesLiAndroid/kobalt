package com.beust.kobalt.api

import com.beust.kobalt.TaskResult

/**
 * Plug-ins that will be invoked during the "assemble" task.
 */
interface IAssemblyContributor : IContributor {
    fun assemble(project: Project, context: KobaltContext) : TaskResult
}
