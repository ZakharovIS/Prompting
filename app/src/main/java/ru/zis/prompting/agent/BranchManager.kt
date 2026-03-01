package ru.zis.prompting.agent

import ru.zis.prompting.data.InputMessage

class BranchManager {
    private val branches = linkedMapOf<String, MutableList<InputMessage>>()
    private val checkpoints = linkedMapOf<String, List<InputMessage>>()
    private var activeBranchName: String = MAIN_BRANCH

    init {
        branches[MAIN_BRANCH] = mutableListOf()
    }

    fun clear() {
        branches.clear()
        checkpoints.clear()
        activeBranchName = MAIN_BRANCH
        branches[MAIN_BRANCH] = mutableListOf()
    }

    fun appendToActive(message: InputMessage) {
        val history = branches.getOrPut(activeBranchName) { mutableListOf() }
        history += message
    }

    fun activeHistory(): List<InputMessage> =
        branches[activeBranchName]?.toList().orEmpty()

    fun branchNames(): List<String> = branches.keys.toList()

    fun checkpointNames(): List<String> = checkpoints.keys.toList()

    fun activeBranch(): String = activeBranchName

    fun saveCheckpoint(name: String): Boolean {
        val normalized = name.trim()
        if (normalized.isBlank()) return false
        checkpoints[normalized] = activeHistory()
        return true
    }

    fun createBranch(checkpointName: String, branchName: String): Boolean {
        val cp = checkpointName.trim()
        val branch = branchName.trim()
        if (cp.isBlank() || branch.isBlank()) return false
        if (branches.containsKey(branch)) return false

        val checkpointHistory = checkpoints[cp] ?: return false
        branches[branch] = checkpointHistory.toMutableList()
        return true
    }

    fun switchBranch(name: String): Boolean {
        val normalized = name.trim()
        if (!branches.containsKey(normalized)) return false
        activeBranchName = normalized
        return true
    }

    fun snapshotBranches(): Map<String, List<InputMessage>> =
        branches.mapValues { (_, v) -> v.toList() }

    fun snapshotCheckpoints(): Map<String, List<InputMessage>> = checkpoints.toMap()

    fun restore(
        savedBranches: Map<String, List<InputMessage>>,
        savedCheckpoints: Map<String, List<InputMessage>>,
        activeBranch: String?,
        fallbackMainHistory: List<InputMessage>
    ) {
        branches.clear()
        checkpoints.clear()

        if (savedBranches.isNotEmpty()) {
            savedBranches.forEach { (name, history) ->
                branches[name] = history.toMutableList()
            }
        }

        if (branches.isEmpty()) {
            branches[MAIN_BRANCH] = fallbackMainHistory.toMutableList()
        }

        checkpoints.putAll(savedCheckpoints)

        val restoredActive = activeBranch?.takeIf { branches.containsKey(it) }
        activeBranchName = restoredActive ?: MAIN_BRANCH
        branches.putIfAbsent(MAIN_BRANCH, mutableListOf())
    }

    companion object {
        const val MAIN_BRANCH = "main"
    }
}
