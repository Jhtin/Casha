package com.example.casha

import com.example.casha.data.AssistantChatSession
import com.example.casha.data.AssistantChatTurn
import org.junit.Assert.*
import org.junit.Test

class AssistantChatStoreTest {
    @Test
    fun roundTripSerializesAndDeserializesTurns() {
        val turns = listOf(
            AssistantChatTurn(
                id = "t1",
                question = "How much did I spend yesterday?",
                answer = "You spent ₱350.00 yesterday on groceries.",
                timestamp = 1700000000000L,
                isError = false
            ),
            AssistantChatTurn(
                id = "t2",
                question = "Can I afford dinner?",
                answer = "Your safe-to-spend balance today is ₱1,200.00.",
                timestamp = 1700000060000L,
                isError = false
            )
        )
        val encoded = AssistantChatTurn.encodeList(turns)
        val decoded = AssistantChatTurn.decodeList(encoded)
        assertEquals(2, decoded.size)
        assertEquals("t1", decoded[0].id)
        assertEquals("How much did I spend yesterday?", decoded[0].question)
        assertEquals("You spent ₱350.00 yesterday on groceries.", decoded[0].answer)
        assertEquals(1700000000000L, decoded[0].timestamp)
        assertFalse(decoded[0].isError)
        assertEquals("t2", decoded[1].id)
        assertEquals("Can I afford dinner?", decoded[1].question)
    }

    @Test
    fun emptyOrMalformedJsonReturnsEmptyList() {
        assertEquals(emptyList<AssistantChatTurn>(), AssistantChatTurn.decodeList(""))
        assertEquals(emptyList<AssistantChatTurn>(), AssistantChatTurn.decodeList("[]"))
        assertEquals(emptyList<AssistantChatTurn>(), AssistantChatTurn.decodeList("{not_an_array}"))
    }

    @Test
    fun roundTripSerializesAndDeserializesSessions() {
        val session = AssistantChatSession(
            id = "s1",
            title = "Trip to Baguio budget",
            timestamp = 1700000000000L,
            turns = listOf(
                AssistantChatTurn(
                    id = "t1",
                    question = "estimate 5k for Baguio trip",
                    answer = "Your remaining money is insufficient.",
                    timestamp = 1700000000000L,
                    isError = false
                )
            )
        )
        val encoded = AssistantChatSession.encodeList(listOf(session))
        val decoded = AssistantChatSession.decodeList(encoded)
        assertEquals(1, decoded.size)
        assertEquals("s1", decoded[0].id)
        assertEquals("Trip to Baguio budget", decoded[0].title)
        assertEquals(1, decoded[0].turns.size)
        assertEquals("estimate 5k for Baguio trip", decoded[0].turns[0].question)
    }
}
