package ru.zis.prompting.db

import ru.zis.prompting.agent.InvariantItem

class InvariantRepository(
    private val dao: InvariantDao
) {

    suspend fun getAll(): List<InvariantItem> = dao.getAll().map { it.toModel() }

    suspend fun upsert(item: InvariantItem) {
        dao.upsert(item.toEntity())
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}

private fun InvariantEntity.toModel() = InvariantItem(
    id = id,
    rule = rule,
    createdAt = createdAt
)

private fun InvariantItem.toEntity() = InvariantEntity(
    id = id,
    rule = rule,
    createdAt = createdAt
)
