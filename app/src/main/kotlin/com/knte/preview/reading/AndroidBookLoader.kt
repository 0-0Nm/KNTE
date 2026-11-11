package com.knte.preview.reading

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.knte.core.reading.BookTextCodec
import java.io.ByteArrayOutputStream

/** Opens only the exact user-selected document; no directory search and no write API. */
class AndroidBookLoader(private val resolver:ContentResolver):BookInputLoader {
    override fun load(reference:String):BookInput {
        val uri=Uri.parse(reference)
        require(uri.scheme=="content") { "BOOK_CONTENT_URI_REQUIRED" }
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
            check(c.moveToFirst());val n=c.getString(0)
            if(!c.isNull(1)) require(c.getLong(1)<=8L*1024*1024) { "BOOK_SIZE_LIMIT" }
            n
        } ?: error("BOOK_NAME_MISSING")
        val format=when {
            name.endsWith(".md",true)->"md"
            name.endsWith(".txt",true)->"txt"
            else->error("FORMAT_UNSUPPORTED")
        }
        val bytes=resolver.openInputStream(uri)?.use { input ->
            val out=ByteArrayOutputStream();val block=ByteArray(8192);var empty=0
            while(true) {
                val n=input.read(block);if(n<0)break
                if(n==0){check(++empty<=8);continue};empty=0
                require(out.size()+n<=8*1024*1024) { "BOOK_READ_BOUND" }
                out.write(block,0,n)
            };out.toByteArray()
        } ?: error("BOOK_INPUT_UNAVAILABLE")
        return BookInput(bytes,reference,name,format)
    }
}
