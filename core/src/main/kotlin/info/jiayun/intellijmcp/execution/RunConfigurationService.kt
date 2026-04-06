package info.jiayun.intellijmcp.execution

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
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
        return runManager.allSettings
            .filter { type == null || it.type.displayName.equals(type, ignoreCase = true) }
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

        // Listen for test results
        val testConnection = project.messageBus.connect()
        testConnection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
            override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
            override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
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

        // Listen for process termination
        val executionConnection = project.messageBus.connect()
        executionConnection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                if (env.runnerAndConfigurationSettings?.name == name) {
                    processHandlerRef.set(handler)
                    handler.addProcessListener(object : ProcessAdapter() {
                        override fun processTerminated(event: ProcessEvent) {
                            exitCodeRef.set(event.exitCode)
                            latch.countDown()
                        }
                    })
                }
            }
        })

        // Start execution on EDT
        ApplicationManager.getApplication().invokeAndWait {
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        }

        // Wait for completion
        val completed = latch.await(timeout, TimeUnit.MILLISECONDS)

        // Clean up listeners
        testConnection.disconnect()
        executionConnection.disconnect()

        // Store results
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
            // Kill the process on timeout
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
            ?: throw NoTestResultsException("No test results for execution: $id (not a test configuration?)")

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
