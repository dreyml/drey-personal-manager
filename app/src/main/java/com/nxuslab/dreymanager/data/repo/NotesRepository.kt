package com.nxuslab.dreymanager.data.repo

import com.nxuslab.dreymanager.data.db.ChecklistEntity
import com.nxuslab.dreymanager.data.db.ChecklistItemEntity
import com.nxuslab.dreymanager.data.db.NoteEntity
import com.nxuslab.dreymanager.data.db.NotesDao
import com.nxuslab.dreymanager.data.db.ReminderEntity
import com.nxuslab.dreymanager.domain.notes.Repeat
import com.nxuslab.dreymanager.domain.notes.ReminderRule
import com.nxuslab.dreymanager.domain.notes.ReminderSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class Note(val id: Long, val title: String, val body: String, val color: Int, val pinned: Boolean, val updatedAt: Long)
data class CheckItem(val id: Long, val text: String, val done: Boolean)
data class Checklist(val id: Long, val title: String, val updatedAt: Long, val items: List<CheckItem>) {
    val doneCount: Int get() = items.count { it.done }
}
data class Reminder(val id: Long, val text: String, val rule: ReminderRule, val nextAt: LocalDateTime?, val doneAt: Long?)

/** Agenda e cancela o alarme de um lembrete (implementado com AlarmManager). */
interface ReminderScheduler {
    fun schedule(id: Long, atMillis: Long)
    fun cancel(id: Long)
}

/** Notas, listas e lembretes. Separado das finanças e da água. */
class NotesRepository(
    private val dao: NotesDao,
    private val scheduler: ReminderScheduler,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    // ---------- Notas ----------
    val notes: Flow<List<Note>> = dao.observeNotes().map { list -> list.map { Note(it.id, it.title, it.body, it.color, it.pinned, it.updatedAt) } }

    suspend fun note(id: Long): Note? = dao.note(id)?.let { Note(it.id, it.title, it.body, it.color, it.pinned, it.updatedAt) }

    /** Salva e devolve o id. Nota nova vazia não é criada; nota existente esvaziada é apagada. */
    suspend fun saveNote(note: Note): Long {
        val blank = note.title.isBlank() && note.body.isBlank()
        val now = System.currentTimeMillis()
        return when {
            note.id == 0L && blank -> 0L
            note.id == 0L -> dao.insertNote(NoteEntity(0, note.title, note.body, note.color, note.pinned, now))
            blank -> { dao.deleteNote(note.id); 0L }
            else -> { dao.updateNote(NoteEntity(note.id, note.title, note.body, note.color, note.pinned, now)); note.id }
        }
    }

    suspend fun deleteNote(id: Long) = dao.deleteNote(id)

    // ---------- Listas ----------
    val lists: Flow<List<Checklist>> = combine(dao.observeLists(), dao.observeItems()) { lists, items ->
        val byList = items.groupBy { it.listId }
        lists.map { l ->
            val its = byList[l.id].orEmpty().map { CheckItem(it.id, it.text, it.done) }
            Checklist(l.id, l.title, l.updatedAt, its.filter { !it.done } + its.filter { it.done })
        }
    }

    suspend fun createList(title: String): Long = dao.insertList(ChecklistEntity(0, title.trim(), System.currentTimeMillis()))

    suspend fun renameList(id: Long, title: String) {
        dao.list(id)?.let { dao.updateList(it.copy(title = title.trim(), updatedAt = System.currentTimeMillis())) }
    }

    suspend fun addItem(listId: Long, text: String) {
        if (text.isBlank()) return
        dao.insertItem(ChecklistItemEntity(0, listId, text.trim(), false, dao.maxPosition(listId) + 1))
        dao.touchList(listId, System.currentTimeMillis())
    }

    suspend fun setItemDone(listId: Long, itemId: Long, done: Boolean) {
        dao.setItemDone(itemId, done)
        dao.touchList(listId, System.currentTimeMillis())
    }

    suspend fun deleteItem(itemId: Long) = dao.deleteItem(itemId)
    suspend fun clearDone(listId: Long) = dao.clearDone(listId)

    suspend fun deleteList(id: Long) {
        dao.deleteItemsOf(id)
        dao.deleteList(id)
    }

    // ---------- Lembretes ----------
    val reminders: Flow<List<Reminder>> = dao.observeReminders().map { list -> list.map { it.toDomain() } }

    suspend fun reminder(id: Long): Reminder? = dao.reminder(id)?.toDomain()

    /** Cria ou altera e agenda o próximo aviso. Devolve o id. */
    suspend fun saveReminder(id: Long, text: String, rule: ReminderRule): Long {
        val next = ReminderSchedule.next(rule, clock())
        val entity = ReminderEntity(
            id = id, text = text.trim(), startDate = rule.startDate, minuteOfDay = rule.minuteOfDay,
            repeat = rule.repeat.name, weekDays = rule.weekDays, nextAt = next?.let(::millis), doneAt = null,
            createdAt = dao.reminder(id)?.createdAt ?: System.currentTimeMillis(),
        )
        val savedId = if (id == 0L) dao.insertReminder(entity) else { dao.updateReminder(entity); id }
        schedule(savedId, entity.nextAt)
        return savedId
    }

    suspend fun deleteReminder(id: Long) {
        scheduler.cancel(id)
        dao.deleteReminder(id)
    }

    /** "Feito" na lista: uma vez some para Feitos; repetido pula para a próxima ocorrência. */
    suspend fun done(id: Long) {
        val e = dao.reminder(id) ?: return
        val r = e.toDomain()
        if (r.rule.repeat == Repeat.ONCE) {
            scheduler.cancel(id)
            dao.updateReminder(e.copy(nextAt = null, doneAt = System.currentTimeMillis()))
        } else {
            val from = maxOf(clock(), r.nextAt ?: clock())
            val next = ReminderSchedule.next(r.rule, from)?.let(::millis)
            dao.updateReminder(e.copy(nextAt = next))
            schedule(id, next)
        }
    }

    /** "Feito" no aviso: o repetido já avançou ao tocar; só o de uma vez muda. */
    suspend fun doneFromNotification(id: Long) {
        val e = dao.reminder(id) ?: return
        if (e.repeat == Repeat.ONCE.name) done(id)
    }

    suspend fun snooze(id: Long) {
        val e = dao.reminder(id) ?: return
        val at = millis(clock().plusHours(1).withSecond(0).withNano(0))
        dao.updateReminder(e.copy(nextAt = at, doneAt = null))
        schedule(id, at)
    }

    /** O alarme tocou: o repetido já agenda a próxima ocorrência; o de uma vez fica pendente. */
    suspend fun fired(id: Long) {
        val e = dao.reminder(id) ?: return
        val r = e.toDomain()
        if (r.rule.repeat == Repeat.ONCE) return
        val next = ReminderSchedule.next(r.rule, clock())?.let(::millis)
        dao.updateReminder(e.copy(nextAt = next))
        schedule(id, next)
    }

    /** Depois de reiniciar o celular ou abrir o app: agenda de novo tudo que ainda vai tocar. */
    suspend fun rescheduleAll() {
        val now = clock()
        dao.scheduledReminders().forEach { e ->
            val r = e.toDomain()
            val at = r.nextAt ?: return@forEach
            when {
                at.isAfter(now) -> scheduler.schedule(e.id, millis(at))
                r.rule.repeat != Repeat.ONCE -> {
                    val next = ReminderSchedule.next(r.rule, now)?.let(::millis)
                    dao.updateReminder(e.copy(nextAt = next))
                    schedule(e.id, next)
                }
            }
        }
    }

    private fun schedule(id: Long, at: Long?) {
        if (at != null && at > System.currentTimeMillis()) scheduler.schedule(id, at) else scheduler.cancel(id)
    }

    private fun millis(t: LocalDateTime): Long = t.atZone(zone()).toInstant().toEpochMilli()

    private fun ReminderEntity.toDomain() = Reminder(
        id = id, text = text,
        rule = ReminderRule(startDate, minuteOfDay, runCatching { Repeat.valueOf(repeat) }.getOrDefault(Repeat.ONCE), weekDays),
        nextAt = nextAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone()) },
        doneAt = doneAt,
    )
}
