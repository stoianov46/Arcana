package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Attachments: picture paths in a message, image checks and encoding, images in the request but not on disk, pictures copied into the vault, text files and PDFs. */
class AttachmentsTest {
    @Test
    fun aPictureNamedByPathInTheTextBecomesAnAttachment() {
        val dir = Files.createTempDirectory("pasted")
        val plain = dir.resolve("shot.png").also { Files.write(it, byteArrayOf(1)) }
        val spaced = dir.resolve("my shot.jpg").also { Files.write(it, byteArrayOf(1)) }
        val (one, rest) = ImageFiles.pastedPaths("what is in $plain ?", null)
        assertEquals(listOf(plain), one); assertEquals("what is in ?", rest)
        val (two, rest2) = ImageFiles.pastedPaths("look ${spaced.toString().replace(" ", "\\ ")} and '$plain'", null)
        assertEquals(listOf(spaced, plain), two); assertEquals("look and", rest2)
        // a bare slash, a root or a word without a file name is no picture and must not crash (it did: "Android / Kotlin")
        val slash = "Compose Multiplatform / Kotlin Multiplatform, see / and /."
        assertEquals(emptyList(), ImageFiles.pastedPaths(slash, null).first)
        assertEquals(slash, ImageFiles.pastedPaths(slash, null).second)
        assertTrue(!ImageFiles.isImage(java.nio.file.Path.of("/")))
        // not an image, not there, or not a path: the text is left alone
        val txt = dir.resolve("a.txt").also { Files.writeString(it, "x") }
        val same = "read $txt and ${dir.resolve("none.png")} and ./shot.png"
        assertEquals(emptyList(), ImageFiles.pastedPaths(same, null).first)
        assertEquals(same, ImageFiles.pastedPaths(same, null).second)
    }

    @Test
    fun imagesAreValidatedAndEncoded() {
        val dir = Files.createTempDirectory("img")
        val png = dir.resolve("a.PNG").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val txt = dir.resolve("note.txt").also { Files.writeString(it, "x") }
        assertNull(ImageFiles.validate(listOf(png)))
        assertTrue(ImageFiles.validate(listOf(txt))!!.contains("not a PNG"))
        assertTrue(ImageFiles.validate(listOf(png), already = 5)!!.contains("at most 5"))
        assertTrue(ImageFiles.validate(listOf(dir.resolve("missing.png")))!!.contains("not a file"))
        val loaded = ImageFiles.load(png)
        assertEquals("a.PNG", loaded.name)
        assertEquals("AQID", loaded.base64)
        assertEquals("1.5 MB", ImageFiles.size(1_572_864))
    }

    @Test
    fun imagesTravelInTheRequestButNotOnDisk() {
        val message = ChatMessage.user("what is this?", listOf(ImageAttachment("a.png", "QUJD", 3)))
        val request = OllamaProtocol.chatRequest(AppConfig(), listOf(message)).toString()
        assertTrue("\"images\":[\"QUJD\"]" in request, request)
        assertTrue("images" !in OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.user("hi"))).toString())
        val stored = message.withoutImageData()
        assertTrue(stored.images.isEmpty())
        assertEquals(listOf("a.png"), stored.imageNames)
        assertEquals(listOf("vision", "completion"), OllamaProtocol.parseCapabilities("""{"capabilities":["vision","completion"]}"""))
    }

    @Test
    fun picturesAreCopiedIntoTheVaultWithUniqueNames() {
        val vault = Vault(Files.createTempDirectory("vault"))
        val src = Files.createTempDirectory("src").resolve("shot.png").also { Files.write(it, byteArrayOf(9)) }
        assertEquals("shot.png", vault.copyAttachment("Arcana", src))
        assertEquals("shot 2.png", vault.copyAttachment("Arcana", src))
        assertTrue(Files.exists(vault.root.resolve("Arcana/attachments/shot 2.png")))
        assertEquals("shot.png", vault.copyAttachment("", src))
        assertTrue(Files.exists(vault.root.resolve("attachments/shot.png")), "a blank folder means the vault root")
    }

    @Test
    fun textFilesAreSentInsideTheMessage() {
        val dir = Files.createTempDirectory("arcana-files")
        val json = dir.resolve("data.json").also { Files.writeString(it, """{"a": 1}""") }
        val bin = dir.resolve("blob.txt").also { Files.write(it, byteArrayOf(65, 0, 66)) }
        assertTrue(TextFiles.isText(json) && !ImageFiles.isImage(json) && TextFiles.isAttachable(json))
        assertNull(TextFiles.validate(listOf(json)))
        assertFailsWith<IllegalArgumentException> { TextFiles.load(bin) }
        val file = TextFiles.load(json)
        val request = OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.user("what is a?", files = listOf(file)))).toString()
        assertTrue("what is a?" in request && "Attached file" in request && "data.json" in request && "files" !in request)
    }

    @Test
    fun pdfTextIsExtracted() {
        val dir = Files.createTempDirectory("arcana-pdf")
        fun pdf(name: String, text: String?) = dir.resolve(name).also { path ->
            org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
                val page = org.apache.pdfbox.pdmodel.PDPage()
                doc.addPage(page)
                if (text != null) org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { c ->
                    c.beginText()
                    c.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
                    c.newLineAtOffset(50f, 700f)
                    c.showText(text)
                    c.endText()
                }
                doc.save(path.toFile())
            }
        }
        val good = pdf("report.pdf", "Quarterly revenue grew")
        assertTrue(TextFiles.isPdf(good) && TextFiles.isAttachable(good) && TextFiles.validate(listOf(good)) == null)
        assertTrue("Quarterly revenue grew" in TextFiles.load(good).text)
        val blank = pdf("scan.pdf", null)
        assertTrue(assertFailsWith<IllegalArgumentException> { TextFiles.load(blank) }.message!!.contains("no text layer"))
        val fake = dir.resolve("fake.pdf").also { Files.writeString(it, "not a pdf") }
        assertFailsWith<IllegalArgumentException> { TextFiles.load(fake) }
    }
}
