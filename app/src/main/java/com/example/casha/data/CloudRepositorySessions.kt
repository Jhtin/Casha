package com.example.casha.data

/** The foreground and WorkManager must share one writer for an account's disk journal. */
object CloudRepositorySessions {
    private data class Session(val repository: CloudLoanRepository, var users: Int)
    private val sessions = mutableMapOf<String, Session>()

    @Synchronized fun acquire(uid: String, create: () -> CloudLoanRepository): CloudLoanRepository {
        val session = sessions[uid] ?: Session(create(), 0).also { sessions[uid] = it }
        session.users++
        return session.repository
    }

    @Synchronized fun release(uid: String, repository: CloudLoanRepository) {
        val session = sessions[uid] ?: return
        if (session.repository !== repository) return
        session.users--
        if (session.users == 0) {
            sessions.remove(uid)
            repository.close()
        }
    }
}
