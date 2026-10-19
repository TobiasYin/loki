package dev.thor.tools.diagnostic

import android.content.Context
import android.content.pm.PackageManager
import dev.thor.tools.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Opaque file IDs only: no caller paths, shell strings, public writable staging or JS binary copies. */
class NativeDownloads(private val context: Context) {
    private val root=File(context.filesDir,"downloads").apply {mkdirs()}
    private var reserved=0L
    @Synchronized private fun reserve(max:Int) {
        val committed=root.listFiles().orEmpty().filter {it.extension!="part"}.sumOf {it.length()}
        requireTool(committed+reserved+max<=512L*1024*1024 && root.usableSpace>max+16L*1024*1024,Code.LIMIT_EXCEEDED,"Download storage budget exhausted")
        reserved+=max
    }
    @Synchronized private fun release(max:Int) {reserved-=max}
    private fun file(id:String,ext:String):File {
        requireTool(runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false),message="Invalid download ID")
        return File(root,"$id.$ext")
    }
    fun apkFile(id:String)=file(id,"bin").also {requireTool(it.isFile,Code.IO_ERROR,"Download missing")}
    private fun digest(f:File,check:()->Unit={}):String {
        val md=MessageDigest.getInstance("SHA-256")
        f.inputStream().use {input->val b=ByteArray(65536);while(true){check();val n=input.read(b);if(n<0)break;md.update(b,0,n)}}
        return md.digest().joinToString(""){"%02x".format(it)}
    }
    @Suppress("DEPRECATION") private fun apk(f:File):JSONObject? {
        val info=context.packageManager.getPackageArchiveInfo(f.path,PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS) ?: return null
        val signers=info.signingInfo?.apkContentsSigners.orEmpty()
        if(signers.isEmpty())return null
        info.applicationInfo?.apply {sourceDir=f.path;publicSourceDir=f.path}
        return JSONObject().put("packageName",info.packageName).put("versionName",info.versionName).put("versionCode",info.longVersionCode)
            .put("label",info.applicationInfo?.loadLabel(context.packageManager)?.toString() ?: info.packageName)
            .put("signerSha256",JSONArray(signers.map {s->MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}}))
            .put("requestedPermissions",JSONArray(info.requestedPermissions.orEmpty().toList()))
    }
    @Synchronized fun info(id:String,check:()->Unit={}):JSONObject {
        val meta=file(id,"json");requireTool(meta.isFile,Code.IO_ERROR,"Download metadata missing")
        val record=JSONObject(meta.readText());val f=apkFile(id)
        requireTool(record.getLong("size")==f.length() && record.getString("sha256")==digest(f,check),Code.HASH_CONFLICT,"Downloaded file changed")
        apk(f)?.let {record.put("apk",it)}
        return record
    }
    fun installPreview(id:String,expected:String):JSONObject {
        val record=info(id)
        requireTool(record.getString("sha256")==expected,Code.HASH_CONFLICT,"Download SHA-256 differs")
        requireTool(record.has("apk"),Code.INVALID_ARGUMENT,"File is not a signed standalone APK")
        requireTool(record.getJSONObject("apk").getString("packageName")!=context.packageName,Code.COMMAND_BLOCKED,"Agent self replacement is not supported")
        return record.put("policyTier","gray").put("requiresApproval",true).put("operation","通过 ADB 安装此 APK；可能新增应用或覆盖同签名的旧版本，不授予额外权限，不允许降级")
    }
    private fun url(raw:String):java.net.URL {
        val u=runCatching {URI(raw)}.getOrNull()
        requireTool(u!=null && u.scheme in setOf("http","https") && u.rawUserInfo==null && u.rawFragment==null && raw.length<=8192,message="HTTP/HTTPS URL required without embedded credentials or fragment")
        return u!!.toURL()
    }
    private fun open(raw:String,method:String,headers:Map<String,String>,body:ByteArray?,cp:Checkpoint):HttpURLConnection {
        var next=url(raw);var verb=method;var payload=body;var currentHeaders=headers
        repeat(9) {hop->
            cp.check();val c=next.openConnection() as HttpURLConnection
            c.instanceFollowRedirects=false;c.connectTimeout=10000;c.readTimeout=10000;c.requestMethod=verb
            cp.cancellation.register {c.disconnect()}
            currentHeaders.forEach { (n,v)->c.setRequestProperty(n,v) }
            try {
                if(payload!=null){c.doOutput=true;c.setFixedLengthStreamingMode(payload!!.size);c.outputStream.use {it.write(payload)}}
                cp.check();val status=c.responseCode;cp.check()
                if(status !in setOf(301,302,303,307,308))return c
                requireTool(hop<8,Code.LIMIT_EXCEEDED,"Too many redirects")
                val location=c.getHeaderField("Location") ?: return c
                val dest=url(next.toURI().resolve(location).toString())
                if(next.protocol!=dest.protocol || next.host!=dest.host || next.port!=dest.port)currentHeaders=emptyMap()
                if(status==303 && verb!="HEAD" || status in setOf(301,302) && verb=="POST"){verb="GET";payload=null}
                next=dest;c.disconnect()
            } catch(t:Exception){c.disconnect();throw t}
        }
        fail(Code.LIMIT_EXCEEDED,"Too many redirects")
    }
    fun execute(tool:String,args:Map<String,Any?>,cp:Checkpoint):Map<String,Any?> {
        if(tool=="files.download_info")return if(args["downloadId"]!=null)info(args.string("downloadId")){cp.check()}.args() else {
            val records=root.listFiles().orEmpty().filter {it.extension=="json"}.sortedByDescending {it.lastModified()}.take(20).map {cp.check();JSONObject(it.readText()).args()}
            mapOf("downloads" to records)
        }
        val download=tool=="files.download"
        val max=args.int("maxBytes",if(download)128*1024*1024 else 65536)
        val method=if(download)"GET" else args.string("method","GET")
        requireTool(method in setOf("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"),message="Unsupported HTTP method")
        requireTool(max in 1..if(download)256*1024*1024 else 65536,Code.LIMIT_EXCEEDED,"Invalid byte budget")
        val body=(args["body"] as? String)?.toByteArray()
        requireTool(body==null || body.size<=65536 && method !in setOf("GET","HEAD"),message="Body requires a write method and at most 64 KiB")
        val headers=linkedMapOf("User-Agent" to "Loki/0.6", "Accept" to "*/*")
        @Suppress("UNCHECKED_CAST") val supplied=args["headers"] as? List<Map<String,Any?>> ?: emptyList()
        requireTool(supplied.sumOf {it.string("name").length+it.string("value").length}<=16384,Code.LIMIT_EXCEEDED,"HTTP headers exceed 16 KiB")
        for(h in supplied) {
            val n=h.string("name");val v=h.string("value")
            requireTool(n.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")) && v.none {it=='\r'||it=='\n'||it=='\u0000'},message="Invalid HTTP header")
            requireTool(n.lowercase() !in setOf("host","connection","content-length","transfer-encoding","proxy-authorization","cookie","set-cookie"),Code.PERMISSION_MISSING,"Transport/credential header is Native managed")
            headers[n]=v
        }
        val raw=args.string("url")
        val c=open(raw,method,headers,body,cp)
        try {
            val status=c.responseCode
            if(!download) {
                val out=java.io.ByteArrayOutputStream();var truncated=false
                val input=if(status>=400)c.errorStream else c.inputStream
                input?.use {stream->val b=ByteArray(8192);while(true){cp.check();val n=stream.read(b);if(n<0)break;val accepted=minOf(n,max-out.size());if(accepted>0)out.write(b,0,accepted);if(accepted<n){truncated=true;break}}}
                var headerBytes=0
                val selected=linkedMapOf<String,String>()
                for((name,values) in c.headerFields) {
                    if(name==null || name.lowercase() in setOf("set-cookie","set-cookie2","authorization","proxy-authenticate"))continue
                    val value=values.joinToString(", ").take(1024)
                    if(selected.size>=32 || headerBytes+name.length+value.length>4096)continue
                    selected[name]=value;headerBytes+=name.length+value.length
                }
                var text=out.toString("UTF-8")
                fun result()=mapOf("url" to c.url.toString(),"status" to status,"headers" to selected,"text" to text,"truncated" to truncated,"size" to out.size())
                // Preserve a complete JSON tool result, including escape expansion and metadata.
                // Runtime's generic byte truncation must never cut this JSON mid-field.
                while((json(result()) as JSONObject).toString().toByteArray().size>56000) {
                    text=text.take(text.length/2);truncated=true
                }
                return result()
            }
            requireTool(status in 200..299,Code.IO_ERROR,"Download HTTP_$status")
            requireTool(c.contentLengthLong<=max,Code.LIMIT_EXCEEDED,"Download exceeds byte limit")
            val filename=args.string("filename",c.url.path.substringAfterLast('/').take(120).ifBlank {"download.bin"})
            requireTool(filename.length in 1..120 && filename.none {it=='/'||it=='\\'||it.isISOControl()} && filename !in setOf(".",".."),message="Invalid display filename")
            val id=UUID.randomUUID().toString();val partial=file(id,"part");val target=file(id,"bin");val meta=file(id,"json")
            // Durable downloads have an explicit aggregate budget; never evict existing user files silently.
            reserve(max)
            try {
                var bytes=0L
                c.inputStream.use {input->partial.outputStream().use {out->val b=ByteArray(65536);while(true){cp.check();val n=input.read(b);if(n<0)break;bytes+=n;requireTool(bytes<=max,Code.LIMIT_EXCEEDED,"Download exceeds byte limit");out.write(b,0,n)};out.fd.sync()}}
                requireTool(bytes>0 && (c.contentLengthLong<0 || bytes==c.contentLengthLong),Code.IO_ERROR,"Incomplete/empty download")
                val sha=digest(partial){cp.check()}
                requireTool(args["sha256"]==null || args["sha256"]==sha,Code.HASH_CONFLICT,"Download checksum mismatch")
                cp.check();requireTool(partial.renameTo(target),Code.IO_ERROR,"Cannot commit download")
                val record=JSONObject().put("downloadId",id).put("filename",filename).put("url",raw).put("finalUrl",c.url.toString()).put("sha256",sha).put("size",bytes).put("mime",c.contentType).put("downloadedAt",System.currentTimeMillis())
                apk(target)?.let {record.put("apk",it)}
                meta.writeText(record.toString());cp.check();return record.args()
            } catch(t:Exception){partial.delete();target.delete();meta.delete();throw t} finally {release(max)}
        } finally {c.disconnect()}
    }
}
