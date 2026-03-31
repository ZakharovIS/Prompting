package ru.zis.prompting.db

import ru.zis.prompting.profile.UserProfile

class UserProfileRepository(
    private val dao: UserProfileDao
) {

    suspend fun getAll(): List<UserProfile> = dao.getAll().map { it.toModel() }

    suspend fun getActive(): UserProfile? = dao.getActive()?.toModel()

    suspend fun save(profile: UserProfile, setActive: Boolean = false) {
        val entity = profile.toEntity(isActive = false)
        dao.upsert(entity)
        if (setActive) dao.setActive(profile.id)
    }

    suspend fun setActive(id: String) {
        dao.setActive(id)
    }

    suspend fun delete(id: String) {
        val active = dao.getActive()
        dao.deleteById(id)

        if (active?.id == id) {
            val next = dao.getAll().firstOrNull()
            if (next != null) {
                dao.setActive(next.id)
            }
        }
    }
}

private fun UserProfileEntity.toModel() = UserProfile(
    id = id,
    name = name,
    style = style,
    constraints = constraints,
    context = context
)

private fun UserProfile.toEntity(isActive: Boolean) = UserProfileEntity(
    id = id,
    name = name,
    style = style,
    constraints = constraints,
    context = context,
    isActive = isActive,
    updatedAt = System.currentTimeMillis()
)
