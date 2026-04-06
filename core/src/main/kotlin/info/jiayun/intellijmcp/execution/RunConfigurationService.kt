package info.jiayun.intellijmcp.execution

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import info.jiayun.intellijmcp.api.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class RunConfigurationService(private val project: Project) {

    private val logger = Logger.getInstance(RunConfigurationService::class.java)

    private data class ExecutionRecord(
        val executionId: String,
        val configurationName: String,
        val testRoot: SMTestProxy.SMRootTestProxy?,
        val exitCode: Int?,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val recentExecutions = ConcurrentHashMap<String, ExecutionRecord>()
    private var latestExecutionId: String? = null

    fun listConfigurations(type: String? = null): List<RunConfigurationInfo> {
        val runManager = RunManager.getInstance(project)
        val filterType = type?.takeIf { it.isNotBlank() }
        return runManager.allSettings
            .filter { filterType == null || it.type.displayName.equals(filterType, ignoreCase = true) }
            .map { settings ->
                RunConfigurationInfo(
                    name = settings.name,
                    type = settings.type.displayName,
                    isTemporary = settings.isTemporary,
                    isShared = settings.isShared
                )
            }
    }

    fun runConfiguration(name: String, timeout: Long = 60_000): ExecutionResult {
        val runManager = RunManager.getInstance(project)
        val settings = runManager.findConfigurationByName(name)
            ?: throw ConfigurationNotFoundException("Run configuration not found: $name")

        val executionId = "${name}-${System.currentTimeMillis()}"
        val executor = DefaultRunExecutor.getRunExecutorInstance()

        val processHandlerRef = AtomicReference<ProcessHandler>()
        val testRootRef = AtomicReference<SMTestProxy.SMRootTestProxy>()
        val exitCodeRef = AtomicInteger(-1)
        val latch = CountDownLatch(1)

        // 1) Listen for SM Test Runner results
        val testConnection = project.messageBus.connect()
        testConnection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
            override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
                logger.info("MCP: onTestingStarted for $name")
            }
            override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                logger.info("MCP: onTestingFinished for $name, tests=${testsRoot.allTests.size}")
                testRootRef.set(testsRoot)
            }
            override fun onTestsCountInSuite(count: Int) {}
            override fun onTestStarted(test: SMTestProxy) {}
            override fun onTestFinished(test: SMTestProxy) {}
            override fun onTestFailed(test: SMTestProxy) {}
            override fun onTestIgnored(test: SMTestProxy) {}
            override fun onSuiteFinished(suite: SMTestProxy) {}
            override fun onSuiteStarted(suite: SMTestProxy) {}
            override fun onCustomProgressTestsCategory(categoryName: String?, testCount: Int) {}
            override fun onCustomProgressTestStarted() {}
            override fun onCustomProgressTestFinished() {}
            override fun onCustomProgressTestFailed() {}
            override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
            override fun onSuiteTreeStarted(suite: SMTestProxy) {}
        })

        // 2) ExecutionListener as fallback — processTerminated with exitCode
        val executionConnection = project.messageBus.connect()
        executionConnection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int
            ) {
                logger.info("MCP: ExecutionListener.processTerminated exitCode=$exitCode")
                if (latch.count > 0) {
                    processHandlerRef.compareAndSet(null, handler)
                    exitCodeRef.set(exitCode)
                    latch.countDown()
                }
            }
        })

        // 3) ProgramRunner.Callback — primary mechanism for getting ProcessHandler
        val callback = object : ProgramRunner.Callback {
            override fun processStarted(descriptor: RunContentDescriptor?) {
                logger.info("MCP: ProgramRunner.Callback.processStarted for $name, descriptor=${descriptor != null}")
                val handler = descriptor?.processHandler
                if (handler != null) {
                    processHandlerRef.set(handler)
                    handler.addProcessListener(object : ProcessAdapter() {
                        override fun processTerminated(event: ProcessEvent) {
                            logger.info("MCP: ProcessAdapter.processTerminated exitCode=${event.exitCode}")
                            exitCodeRef.set(event.exitCode)
                            latch.countDown()
                        }
                    })
                    // Handle already-terminated process
                    if (handler.isProcessTerminated) {
                        exitCodeRef.set(handler.exitCode ?: -1)
                        latch.countDown()
                    }
                } else {
                    // Rider Unit Tests: descriptor is null, no ProcessHandler available.
                    // Count down so we don't timeout — tests are running but we can't track completion.
                    logger.info("MCP: processStarted but no ProcessHandler available for $name, releasing latch")
                    latch.countDown()
                }
            }

            override fun processNotStarted() {
                logger.warn("MCP: ProgramRunner.Callback.processNotStarted for $name")
                latch.countDown()
            }

            override fun processNotStarted(e: Throwable?) {
                logger.warn("MCP: ProgramRunner.Callback.processNotStarted with error for $name", e)
                latch.countDown()
            }
        }

        // 4) Build environment with callback and execute
        ApplicationManager.getApplication().invokeAndWait {
            val env = ExecutionEnvironmentBuilder
                .createOrNull(executor, settings)
                ?.build(callback)
            if (env != null) {
                ProgramRunnerUtil.executeConfigurationAsync(env, true, true, callback)
            } else {
                logger.warn("MCP: Failed to create ExecutionEnvironment for $name")
                latch.countDown()
            }
        }

        // 5) Wait for completion
        val completed = latch.await(timeout, TimeUnit.MILLISECONDS)

        // 6) Grace period — wait for SM Test Runner events or process to settle
        //    For Rider: processStarted fires with null descriptor immediately,
        //    but tests may still be running. Poll for test results.
        val graceDeadline = System.currentTimeMillis() + (timeout - (System.currentTimeMillis() - (executionId.substringAfterLast('-').toLongOrNull() ?: 0))).coerceIn(0, timeout)
        val maxGrace = 30_000L // max 30s grace for Rider-style runners
        val graceEnd = System.currentTimeMillis() + maxGrace.coerceAtMost(graceDeadline - System.currentTimeMillis())
        if (completed && processHandlerRef.get() == null) {
            // No ProcessHandler — likely Rider. Wait for test results with polling.
            logger.info("MCP: No ProcessHandler, waiting for test results via polling (up to ${maxGrace}ms)")
            while (System.currentTimeMillis() < graceEnd && testRootRef.get() == null) {
                Thread.sleep(500)
            }
        } else {
            // Standard case — short grace for SM Test Runner events
            Thread.sleep(500)
        }

        // 7) Clean up listeners
        testConnection.disconnect()
        executionConnection.disconnect()

        // 8) Store results
        val record = ExecutionRecord(
            executionId = executionId,
            configurationName = name,
            testRoot = testRootRef.get(),
            exitCode = if (completed) exitCodeRef.get() else null
        )
        recentExecutions[executionId] = record
        latestExecutionId = executionId

        // Evict old records (keep last 20)
        if (recentExecutions.size > 20) {
            recentExecutions.entries
                .sortedBy { it.value.timestamp }
                .take(recentExecutions.size - 20)
                .forEach { recentExecutions.remove(it.key) }
        }

        if (!completed) {
            processHandlerRef.get()?.destroyProcess()
            throw ExecutionTimeoutException("Execution timed out after ${timeout}ms: $name")
        }

        return ExecutionResult(
            exitCode = exitCodeRef.get(),
            success = exitCodeRef.get() == 0,
            timedOut = false,
            executionId = executionId
        )
    }

    fun getTestResults(
        executionId: String? = null,
        includeOutput: Boolean = false,
        failedOnly: Boolean = false
    ): TestResults {
        val id = executionId ?: latestExecutionId
            ?: throw NoTestResultsException("No test results available")

        val record = recentExecutions[id]
            ?: throw NoTestResultsException("No test results found for execution: $id")

        val root = record.testRoot
        if (root == null) {
            // No SM Test Runner results — return minimal result based on exit code
            val status = when {
                record.exitCode == null -> TestRunStatus.ERROR
                record.exitCode == 0 -> TestRunStatus.PASSED
                else -> TestRunStatus.FAILED
            }
            return TestResults(
                executionId = id,
                configurationName = record.configurationName,
                status = status,
                totalTests = 0,
                passed = 0,
                failed = 0,
                ignored = 0,
                duration = 0,
                tests = emptyList(),
                message = "Structured test results not available for this test framework. Exit code: ${record.exitCode}"
            )
        }

        val allLeafTests = root.allTests.filter { it.isLeaf }
        val tests = allLeafTests
            .filter { !failedOnly || it.isDefect }
            .map { proxy -> toTestCaseResult(proxy, includeOutput) }

        val passed = allLeafTests.count { it.isPassed }
        val failed = allLeafTests.count { it.isDefect }
        val ignored = allLeafTests.count { it.isIgnored }

        val overallStatus = when {
            root.isInProgress -> TestRunStatus.IN_PROGRESS
            root.wasTerminated() -> TestRunStatus.TERMINATED
            failed > 0 -> TestRunStatus.FAILED
            else -> TestRunStatus.PASSED
        }

        return TestResults(
            executionId = id,
            configurationName = record.configurationName,
            status = overallStatus,
            totalTests = allLeafTests.size,
            passed = passed,
            failed = failed,
            ignored = ignored,
            duration = root.duration ?: 0,
            tests = tests
        )
    }

    private fun toTestCaseResult(proxy: AbstractTestProxy, includeOutput: Boolean): TestCaseResult {
        val status = when {
            proxy.isPassed -> TestCaseStatus.PASSED
            proxy.isIgnored -> TestCaseStatus.IGNORED
            proxy.isDefect -> if (proxy.isInterrupted) TestCaseStatus.ERROR else TestCaseStatus.FAILED
            proxy.isInProgress -> TestCaseStatus.IN_PROGRESS
            else -> TestCaseStatus.ERROR
        }

        return TestCaseResult(
            name = proxy.name,
            suite = proxy.parent?.name,
            status = status,
            duration = proxy.duration ?: 0,
            errorMessage = if (proxy.isDefect) proxy.errorMessage else null,
            stackTrace = if (proxy.isDefect) proxy.stacktrace else null,
            output = if (includeOutput) captureOutput(proxy) else null
        )
    }

    private fun captureOutput(proxy: AbstractTestProxy): String? {
        val sb = StringBuilder()
        proxy.printOn(object : com.intellij.execution.testframework.Printer {
            override fun print(text: String, contentType: com.intellij.execution.ui.ConsoleViewContentType) {
                sb.append(text)
            }
            override fun onNewAvailable(composite: com.intellij.execution.testframework.Printable) {}
            override fun printHyperlink(text: String, info: com.intellij.execution.filters.HyperlinkInfo) {
                sb.append(text)
            }
            override fun mark() {}
        })
        return sb.toString().ifEmpty { null }
    }

    companion object {
        fun getInstance(project: Project): RunConfigurationService =
            project.getService(RunConfigurationService::class.java)
    }
}

class ConfigurationNotFoundException(message: String) : Exception(message)
class ExecutionTimeoutException(message: String) : Exception(message)
class NoTestResultsException(message: String) : Exception(message)
