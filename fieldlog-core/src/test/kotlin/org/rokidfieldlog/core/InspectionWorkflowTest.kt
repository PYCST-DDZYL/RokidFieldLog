package org.rokidfieldlog.core

import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.BasicStroke
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

class InspectionWorkflowTest {
    @get:Rule val temp = TemporaryFolder(File("build/inspection-workflow").apply { mkdirs() })
    private val texts = object : AtomicTextFiles {
        override fun read(file: File) = file.readText(Charsets.UTF_8)
        override fun write(file: File, text: String) {
            file.parentFile.mkdirs(); val pending = File(file.path + ".new")
            pending.writeText(text, Charsets.UTF_8)
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private val clock = object : FieldClock { override fun utcMillis() = 123456789L; override fun elapsedNanos() = 50L }
    private val inspector = ImageInspector { file -> ImageIO.read(file)?.let { it.width to it.height } }
    private fun capture(phase: Int): IncomingCapture {
        val id = UUID.randomUUID().toString(); val file = temp.newFile("$id.jpg")
        val image = BufferedImage(960, 720, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(213, 221, 208); fillRect(0, 0, 960, 720)
            color = Color(122, 133, 128); fillRect(150, 100, 670, 600)
            color = Color(224, 228, 221); stroke = BasicStroke(5f)
            for (x in 150..820 step 134) drawLine(x, 100, x, 700)
            for (y in 100..700 step 100) drawLine(150, y, 820, y)
            when (phase) {
                0 -> { color = Color(56, 47, 37); fillOval(350, 340, 220, 140) }
                1 -> { color = Color(41, 63, 97); fillRect(220, 220, 510, 360) }
                else -> { color = Color(156, 165, 157); fillRect(350, 340, 220, 140) }
            }
            color = Color.BLACK; font = Font(Font.SANS_SERIF, Font.BOLD, 28)
            drawString("SYNTHETIC DEMO / " + phase, 24, 48); dispose()
        }
        check(ImageIO.write(image, "jpg", file))
        val hash = PhotoDigest.sha256(file)
        return IncomingCapture(RecordDocument(CaptureRecord.pending(id, null).copy(width = 960, height = 720,
            photoSha256 = hash, photoSha256Source = FingerprintSource.CAPTURE_SAVE, captureTimeSource = "SYNTHETIC_ONLY")), file, hash)
    }
    @Test fun importedPhotosSupportClaimObstructionRevisitVerificationAndDeletionProtection() {
        lateinit var inspections: InspectionRepository
        val library = OfflineLibrary(temp.newFolder("library"), texts, inspector, clock, beforeDelete = { inspections.requireUnlinked(it) })
        inspections = InspectionRepository(temp.newFolder("issues"), texts, clock, InspectionEvidenceSource { id ->
            val entry = library.load(id); InspectionEvidence(entry.original, entry.importedSha256, entry.draft.isSynthetic)
        })
        val captures = (0..2).map(::capture)
        val bytes = ByteArrayOutputStream().also { CapturePackage.write(it, captures, isSynthetic = true) }.toByteArray()
        assertEquals(ImportReport(3, 0, captures.map { it.document.record.photoId }), library.importPackage(ByteArrayInputStream(bytes)))
        val ids = captures.map { it.document.record.photoId }
        val first = inspections.create("合成园区 / 步行道 07", IssueCategory.ROAD_DAMAGE, ids[0], "2020-01-01", true, "合成演示：首次观察到路面破损")
        val claim = inspections.repairClaim(first.issueId, first.revision, "合成演示：维修方反馈已完成，尚未现场核对")
        val blocked = inspections.observe(first.issueId, claim.revision, ids[1], "2020-01-02", InspectionVisibility.OBSTRUCTED,
            InspectionFinding.UNABLE_TO_ASSESS, true, "合成演示：原问题位置被遮挡，无法确认")
        assertEquals(InspectionState.NEEDS_RECHECK, blocked.state)
        assertThrows(IllegalArgumentException::class.java) { inspections.verify(first.issueId, blocked.revision,
            blocked.observations.last().eventId, VerificationDecision.REPAIRED, true, "Invalid closure") }
        val after = inspections.observe(first.issueId, blocked.revision, ids[2], "2020-01-03", InspectionVisibility.CLEAR,
            InspectionFinding.NO_VISIBLE_PROBLEM, true, "合成演示：位置清楚可见，原破损已不可见，等待人工复查")
        assertEquals(InspectionState.AWAITING_VERIFICATION, after.state)
        val verified = inspections.verify(first.issueId, after.revision, after.observations.last().eventId,
            VerificationDecision.REPAIRED, true, "合成演示：人工对照确认，并核对照片属于反馈之后的复查")
        assertEquals(InspectionState.VERIFIED_REPAIRED, verified.state)
        val comparison = inspections.comparison(first.issueId)
        assertEquals(ids[0], comparison.baseline.photoId); assertEquals(ids[2], comparison.latest.photoId)
        assertTrue(verified.isSynthetic)
        assertThrows(IllegalArgumentException::class.java) { library.delete(ids[0]) }
        assertTrue(library.load(ids[0]).original.isFile)
        System.getProperty("fieldlog.demoOutput")?.takeIf { it.isNotBlank() }?.let { path ->
            demo(File(path), listOf(first, claim, blocked, after, verified), captures, bytes)
        }
        inspections.deleteTracking(first.issueId, verified.revision)
        library.delete(ids[0]); assertEquals(2, library.list().size)
        assertTrue(library.load(ids[2]).original.isFile)
    }
    private fun demo(output: File, snapshots: List<TrackedIssue>, captures: List<IncomingCapture>, packageBytes: ByteArray) {
        check(output.isDirectory || output.mkdirs())
        File(output, "SYNTHETIC-inspection-captures.zip").writeBytes(packageBytes)
        File(output, "SYNTHETIC-inspection-history.json").writeText(InspectionCodec.encode(snapshots.last()), Charsets.UTF_8)
        val gson = GsonBuilder().setPrettyPrinting().create()
        val steps = snapshots.map { issue -> linkedMapOf<String, Any>("state" to issue.state.name, "reason" to issue.recheckReason,
            "events" to issue.events, "site" to issue.siteLabel, "revision" to issue.revision) }
        val images = captures.associate { capture -> capture.document.record.photoId to
            ("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(capture.photo.readBytes())) }
        val stepsJson = gson.toJson(steps); val imagesJson = gson.toJson(images)
        File(output, "inspection-demo.html").writeText("""<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FieldLog 合成复查演示</title><style>
body{margin:0;background:#f4f6f1;color:#193b31;font:16px/1.6 system-ui,sans-serif}main{max-width:1060px;margin:auto;padding:32px 20px}
h1{font-size:32px;line-height:1.25}.tag{display:inline-block;background:#dbe9d9;padding:5px 12px;border-radius:20px}.panel{background:white;padding:22px;border-radius:16px;margin:18px 0}
#steps{display:flex;flex-wrap:wrap;gap:8px}button{font:inherit;padding:9px 14px;border:1px solid #99b0a2;border-radius:9px;background:white;color:inherit;cursor:pointer}button.active{background:#185d46;color:white}
.photos{display:grid;grid-template-columns:1fr 1fr;gap:18px}img{width:100%;border-radius:10px}h2{margin:0}small{color:#556b60}li{padding:8px 0}#state{font-size:24px;font-weight:650}@media(max-width:680px){.photos{grid-template-columns:1fr}}
</style><main><span class="tag">合成数据 · 核心流程演示</span><h1>同一处问题，接续每次观察</h1><p>本页使用真实核心状态规则和合成 JPEG 生成，展示可核验的流程。它不是 Android 界面截图，没有现场巡查或维修发生。</p>
<div id="steps"></div><section class="panel"><div id="state"></div><p id="reason"></p><small id="meta"></small></section>
<section class="panel photos"><div><h2>最初观察</h2><img id="before" alt="合成的初始路面破损"><small id="beforeDate"></small></div><div><h2>最近观察</h2><img id="after" alt="所选步骤的最近合成观察"><small id="afterDate"></small></div></section>
<section class="panel"><h2>连续历史</h2><ol id="history"></ol></section><p>未知、遮挡和维修反馈均不会自动变成已修复。最后一步仍是人工确认，结论对应当次可见范围。</p></main>
<script>
const steps=$stepsJson;const images=$imagesJson;
const titles=['发现问题','收到维修反馈','复查被遮挡','清晰新照片待核对','人工复查确认'];
const labels={OPEN:'仍待处理',NEEDS_RECHECK:'需要再次观察',AWAITING_VERIFICATION:'照片待人工复查',VERIFIED_REPAIRED:'已有复查确认记录'};
const kinds={OBSERVATION:'现场观察',REPAIR_CLAIM:'维修反馈',VERIFICATION:'人工复查'};
const controls=document.getElementById('steps');titles.forEach((title,index)=>{const b=document.createElement('button');b.textContent=(index+1)+'. '+title;b.onclick=()=>show(index);controls.appendChild(b)});
function show(index){const step=steps[index];Array.from(controls.children).forEach((b,i)=>{b.classList.toggle('active',i===index);b.setAttribute('aria-pressed',String(i===index))});document.getElementById('state').textContent=labels[step.state];document.getElementById('reason').textContent=step.reason;document.getElementById('meta').textContent=step.site+' · 历史修订 '+step.revision;
const observations=step.events.filter(e=>e.kind==='OBSERVATION');const first=observations[0],last=observations[observations.length-1];document.getElementById('before').src=images[first.photoId];document.getElementById('after').src=images[last.photoId];document.getElementById('beforeDate').textContent=first.observedOn;document.getElementById('afterDate').textContent=last.observedOn+' · '+last.finding;
const history=document.getElementById('history');history.replaceChildren();step.events.forEach(e=>{const li=document.createElement('li');li.textContent=kinds[e.kind]+(e.observedOn?' / '+e.observedOn:'')+'：'+e.note;history.appendChild(li)})}show(0);
</script></html>""", Charsets.UTF_8)
    }
}
