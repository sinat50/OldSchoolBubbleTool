package com.sinat.osrsbubbletool

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

// Every quest's requirements, and every achievement diary task, loaded from QuestData.
// Also works out whether you meet a requirement, using your account (scanned from the Bubble Tool Sync plugin).
object QuestBook {

    // A requirement for a quest or diary task
    sealed class Req {
        class Skill(val skill: String, val level: Int, val boostable: Boolean = false) : Req()
        class QuestDone(val quest: String, val startedOnly: Boolean = false) : Req()
        class QuestPoints(val points: Int) : Req()
        class Combat(val level: Int) : Req()
        class Text(val text: String) : Req()
    }

    class QuestInfo(
        val name: String,
        val members: Boolean,
        val miniquest: Boolean,
        val difficulty: String,
        val questPoints: Int,
        val requirements: List<Req>,
        val enemies: List<String>
    )

    class DiaryTask(val name: String, val steps: List<String>, val requirements: List<Req>, val items: List<String>)
    class DiaryTier(val tier: String, val requirements: List<Req>, val rewards: List<String>, val tasks: List<DiaryTask>)
    class Diary(val region: String, val tiers: List<DiaryTier>)

    val quests: List<QuestInfo> by lazy { load().first }
    val diaries: List<Diary> by lazy { load().second }
    private val byName: Map<String, QuestInfo> by lazy { quests.associateBy { it.name } }

    fun quest(name: String): QuestInfo? = byName[name]

    private var loaded: Pair<List<QuestInfo>, List<Diary>>? = null

    @Synchronized
    private fun load(): Pair<List<QuestInfo>, List<Diary>> {
        loaded?.let { return it }
        val root = JSONObject(QuestData.json)
        val q = root.getJSONArray("quests")
        val quests = List(q.length()) { i ->
            val o = q.getJSONObject(i)
            val type = o.getString("t")
            QuestInfo(o.getString("n"), type != "F", type == "M", o.optString("d"), o.optInt("qp"),
                reqs(o.optJSONArray("r")), strings(o.optJSONArray("e")))
        }
        val d = root.getJSONArray("diaries")
        val diaries = List(d.length()) { i ->
            val o = d.getJSONObject(i)
            val t = o.getJSONArray("t")
            Diary(o.getString("n"), List(t.length()) { j ->
                val tier = t.getJSONObject(j)
                val k = tier.getJSONArray("k")
                DiaryTier(tier.getString("t"), reqs(tier.optJSONArray("g")), strings(tier.optJSONArray("w")),
                    List(k.length()) { n ->
                        val task = k.getJSONObject(n)
                        DiaryTask(task.getString("n"), strings(task.optJSONArray("s")),
                            reqs(task.optJSONArray("r")), strings(task.optJSONArray("i")))
                    })
            })
        }
        return (quests to diaries).also { loaded = it }
    }

    // Every item the tier's unfinished tasks need (all tasks if the account isn't synced: done = null), each once,
    // in the order the tasks come. The same item for two tasks is listed once, and amounts are added up
    // ("Water runes (2)" and "Water rune (6)": "Water runes (8)"), as each task uses up its own. Items are matched
    // ignoring capitals and a plural "s", as Quest Helper writes some both ways.
    fun itemsStillNeeded(tier: DiaryTier, done: List<Boolean>?): List<String> {
        // counted: an amount was given for it somewhere (runes, coins...), so the amounts are added up;
        // otherwise it's a tool or gear (a pickaxe, food, combat gear) and listed once, however many tasks use it
        class Entry(val name: String, var total: Int = 0, var counted: Boolean = false)
        val items = LinkedHashMap<String, Entry>()
        tier.tasks.forEachIndexed { i, task ->
            if (done?.getOrNull(i) == true) return@forEachIndexed
            for (item in task.items) {
                val m = AMOUNT.matchEntire(item)
                val name = m?.groupValues?.get(1) ?: item
                val amount = m?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull()
                val entry = items.getOrPut(name.lowercase().removeSuffix("s")) { Entry(name) }
                entry.total += amount ?: 1
                if (amount != null) entry.counted = true
            }
        }
        // amounts written with thousands commas, like the rest of the Quest Helper ("Coins (30,105)")
        return items.values.map { if (it.counted && it.total > 1) "${it.name} (${"%,d".format(Locale.US, it.total)})" else it.name }
    }
    private val AMOUNT = Regex("""(.*) \(([\d,]+)\)""")

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    private fun reqs(a: JSONArray?): List<Req> {
        if (a == null) return emptyList()
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            when {
                o.has("s") -> Req.Skill(o.getString("s"), o.getInt("l"), o.optInt("b") == 1)
                o.has("q") -> Req.QuestDone(o.getString("q"), o.optInt("st") == 1)
                o.has("qp") -> Req.QuestPoints(o.getInt("qp"))
                o.has("cb") -> Req.Combat(o.getInt("cb"))
                else -> Req.Text(o.optString("x"))
            }
        }
    }

    // ---------------- Checking against your account ----------------

    enum class Met { YES, NO, PARTLY, UNKNOWN }

    class Check(val met: Met, val text: String)

    // Your quest points (added up once for each scanned account: every "N quest points" requirement asks)
    fun questPoints(data: Account): Int {
        // the game's own total, when the code has it (always right, even before the app knows a new quest)
        data.questPoints?.let { return it }
        if (data !== pointsFor) {
            pointsFor = data
            points = quests.filter { data.questState(it.name) == Account.FINISHED }.sumOf { it.questPoints }
            // Recipe for Disaster gives its 10 points one at a time, 1 for each part you finish
            if (data.questState("Recipe for Disaster") != Account.FINISHED) {
                points += data.quests.count { (name, state) -> name.startsWith("Recipe for Disaster - ") && state == Account.FINISHED }
            }
        }
        return points
    }
    private var pointsFor: Account? = null
    private var points = 0

    // Quests (or miniquests) done, out of how many: counted from the scanned code when it has the counts (every
    // quest the game has, even ones the app doesn't know yet), otherwise from the app's own quest list
    fun tally(data: Account, miniquests: Boolean): Account.Tally {
        (if (miniquests) data.miniquestTally else data.questTally)?.let { return it }
        val list = quests.filter { it.miniquest == miniquests }
        return Account.Tally(list.count { data.questState(it.name) == Account.FINISHED }, list.size)
    }

    fun combatLevel(data: Account): Int? {
        fun l(s: String) = data.level(s)
        val att = l("Attack") ?: return null; val str = l("Strength") ?: return null
        val def = l("Defence") ?: return null; val hp = l("Hitpoints") ?: return null
        val pray = l("Prayer") ?: return null; val range = l("Ranged") ?: return null
        val mage = l("Magic") ?: return null
        val base = 0.25 * (def + hp + pray / 2)
        val best = max(0.325 * (att + str), max(0.325 * floor(range * 1.5), 0.325 * floor(mage * 1.5)))
        return floor(base + best).toInt()
    }

    // How a requirement reads in a list without an account ("70 Agility (boostable)", "Dragon Slayer I (started)")
    fun describe(req: Req): String = check(req, null).text

    fun check(req: Req, data: Account?): Check = when (req) {
        is Req.Skill -> {
            val have = data?.level(req.skill)
            val name = "${req.level} ${req.skill}" + if (req.boostable) " (boostable)" else ""
            when {
                have == null -> Check(Met.UNKNOWN, name)
                have >= req.level -> Check(Met.YES, "${req.level} ${req.skill} (you: $have)")
                req.boostable -> Check(Met.PARTLY, "${req.level} ${req.skill} (you: $have, boostable)")
                else -> Check(Met.NO, "${req.level} ${req.skill} (you: $have, need ${req.level - have} more)")
            }
        }
        is Req.QuestDone -> {
            val name = req.quest + if (req.startedOnly) " (started)" else ""
            when (data?.questState(req.quest)) {
                null -> Check(Met.UNKNOWN, name)
                Account.FINISHED -> Check(Met.YES, name)
                Account.IN_PROGRESS ->
                    if (req.startedOnly) Check(Met.YES, name) else Check(Met.PARTLY, "${req.quest} (started, not finished)")
                else -> Check(Met.NO, "$name (not started)")
            }
        }
        is Req.QuestPoints -> {
            if (data == null) Check(Met.UNKNOWN, "${req.points} Quest points")
            else {
                val have = questPoints(data)
                if (have >= req.points) Check(Met.YES, "${req.points} Quest points (you: $have)")
                else Check(Met.NO, "${req.points} Quest points (you: $have)")
            }
        }
        is Req.Combat -> {
            val have = data?.let { combatLevel(it) }
            when {
                have == null -> Check(Met.UNKNOWN, "Combat level ${req.level}")
                have >= req.level -> Check(Met.YES, "Combat level ${req.level} (you: $have)")
                else -> Check(Met.NO, "Combat level ${req.level} (you: $have)")
            }
        }
        is Req.Text -> Check(Met.UNKNOWN, req.text)
    }

    enum class Status { DONE, STARTED, READY, MISSING, UNKNOWN }

    // Where you stand with a quest: finished, can start it, or missing something
    fun status(quest: QuestInfo, data: Account?): Status = standing(quest, data).first

    // The same, with how many requirements are missing (worked out together, for the list of quests)
    fun standing(quest: QuestInfo, data: Account?): Pair<Status, Int> {
        if (data == null) return Status.UNKNOWN to 0
        return when (data.questState(quest.name)) {
            Account.FINISHED -> Status.DONE to 0
            Account.IN_PROGRESS -> Status.STARTED to 0
            null -> Status.UNKNOWN to 0   // the plugin's RuneLite doesn't know this quest yet (a new one): it may already be done
            else -> missing(quest.requirements, data).size.let { if (it == 0) Status.READY to 0 else Status.MISSING to it }
        }
    }

    // The requirements you definitely don't meet (boostable skills count as met)
    fun missing(reqs: List<Req>, data: Account?): List<Req> =
        reqs.filter { r -> check(r, data).met.let { it == Met.NO || (it == Met.PARTLY && r is Req.QuestDone) } }

    fun wikiUrl(page: String): String =
        "https://oldschool.runescape.wiki/w/" + java.net.URLEncoder.encode(page.replace(' ', '_'), "UTF-8")
            .replace("%2F", "/").replace("%21", "!").replace("%27", "'")
}
