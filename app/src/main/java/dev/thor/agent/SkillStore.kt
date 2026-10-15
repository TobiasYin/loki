package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Private immutable revisions + atomically replaced metadata index. Discovery never returns bodies. */
class SkillStore(private val root: File) {
    companion object {
        const val FILE_LIMIT = 65536
        const val PACKAGE_LIMIT = 2 * 1024 * 1024
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        fun manifest(content: String): Pair<String,String> {
            require(content.toByteArray().size <= FILE_LIMIT) { "SKILL.md 限 64 KiB" }
            val normalized = content.removePrefix("\uFEFF").replace("\r\n", "\n")
            require(normalized.startsWith("---\n")) { "需要 YAML frontmatter" }
            val end = normalized.indexOf("\n---\n", 4)
            require(end in 4..8192 && normalized.substring(end+5).isNotBlank()) { "需要有效元数据和正文" }
            val options=LoaderOptions().apply { isAllowDuplicateKeys=false; maxAliasesForCollections=0; nestingDepthLimit=8; codePointLimit=8192 }
            val yaml=Yaml(SafeConstructor(options)).load<Any>(normalized.substring(4,end)) as? Map<*,*> ?: error("元数据必须为映射")
            val name=yaml["name"] as? String ?: error("缺少 name")
            val description=yaml["description"] as? String ?: error("缺少 description")
            require(name.length in 1..64 && name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "name 必须为小写字母、数字和单连字符" }
            require(description.isNotBlank() && description.length <= 1024) { "description 限 1–1024 字符" }
            return name to description.trim()
        }
        fun safePath(path: String): String {
            require(path.length in 1..240 && !path.startsWith('/') && '\\' !in path && ':' !in path && !path.any {it.code<32}) { "无效资源路径" }
            require(path.split('/').all {it.isNotEmpty() && it!="." && it!=".." && !it.startsWith('.')}) { "禁止隐藏路径或目录穿越" }
            return path
        }
        fun bounded(input: InputStream, limit: Int): ByteArray {
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val n=input.read(buffer,0,minOf(buffer.size,limit+1-out.size()));if(n<0)break;if(n==0)continue;out.write(buffer,0,n);require(out.size()<=limit) {"文件超出预算"}}
            return out.toByteArray()
        }
        fun unpack(input: InputStream, zip: Boolean): Map<String,ByteArray> {
            if(!zip)return mapOf("SKILL.md" to bounded(input,FILE_LIMIT))
            val files=linkedMapOf<String,ByteArray>();var total=0;var count=0
            ZipInputStream(input).use { z -> while(true) {
                val e=z.nextEntry ?: break
                require(++count <= 160) {"压缩包条目过多"}
                val path=safePath(e.name.removeSuffix("/"))
                if(e.isDirectory)continue
                val bytes=bounded(z,FILE_LIMIT);total+=bytes.size
                require(total<=PACKAGE_LIMIT && files.size<128 && !files.containsKey(path)) {"压缩包超限或有重复文件"}
                files[path]=bytes
            } }
            val manifests=files.keys.filter {it=="SKILL.md" || it.endsWith("/SKILL.md")}
            require(manifests.size==1) {"每次导入一个 Skill；压缩包中必须只有一个 SKILL.md"}
            val prefix=manifests.single().removeSuffix("SKILL.md")
            require(files.keys.all {it.startsWith(prefix)}) {"资源必须在 Skill 目录内"}
            return files.mapKeys {(key,_)->key.removePrefix(prefix)}
        }
    }
    private val index=File(root,"catalog.json")
    private var entries: MutableList<JSONObject>
    init {
        check(root.isDirectory || root.mkdirs())
        entries=if(index.exists()) JSONArray(index.readText()).objects().toMutableList() else mutableListOf()
    }
    @Synchronized fun catalog(enabledOnly: Boolean=false): JSONArray = JSONArray(entries.filter {!enabledOnly || it.getBoolean("enabled")}.sortedBy {it.getString("name")}.map {entry ->
        JSONObject(entry.toString()).apply {remove("revision")}
    })
    private fun entry(name: String)=entries.singleOrNull {it.getString("name")==name} ?: error("Skill 不存在")
    @Synchronized fun read(name: String, path: String="SKILL.md", enabledOnly: Boolean=false): JSONObject {
        val e=entry(name);require(!enabledOnly || e.getBoolean("enabled")) {"Skill 已停用"}
        val dir=File(root,e.getString("revision"));val file=File(dir,safePath(path))
        require(file.canonicalPath.startsWith(dir.canonicalPath+"/") && file.isFile && file.length()<=FILE_LIMIT)
        return obj("name" to name,"path" to path,"hash" to e.getString("hash"),"content" to utf8(file.readBytes()),
            "resources" to JSONArray(dir.walkTopDown().filter {it.isFile}.map {it.relativeTo(dir).invariantSeparatorsPath}.sorted().toList()),"untrusted" to true)
    }
    @Synchronized fun save(files: Map<String,ByteArray>, expectedHash: String="", builtin: Boolean=false): JSONObject {
        require(files.size in 1..128 && files.values.sumOf {it.size}<=PACKAGE_LIMIT)
        files.forEach {(path,bytes)->safePath(path);require(bytes.size<=FILE_LIMIT)}
        val content=utf8(files["SKILL.md"] ?: error("缺少 SKILL.md"));val(name,description)=manifest(content)
        val previous=entries.singleOrNull {it.getString("name")==name}
        require(previous==null || !previous.getBoolean("builtin") || builtin) {"内置 Skill 只允许停用"}
        require((previous?.getString("hash") ?: "")==expectedHash) {"Skill 已变化，请重新读取"}
        require(previous!=null || entries.size<64) {"最多 64 个 Skill"}
        val revision=UUID.randomUUID().toString();val dir=File(root,revision);check(dir.mkdir())
        try {
            files.forEach {(path,bytes)->val f=File(dir,path);f.parentFile!!.mkdirs();f.outputStream().use {it.write(bytes);it.flush()}}
            val hash=digest(files.toSortedMap().entries.joinToString("\n") {(path,bytes)->"$path:${digest(bytes)}"}.toByteArray())
            val next=obj("name" to name,"description" to description,"enabled" to (previous?.getBoolean("enabled") ?: true),"builtin" to builtin,"hash" to hash,"revision" to revision,"fileCount" to files.size,"updatedAt" to System.currentTimeMillis())
            commit(entries.filter {it!==previous}+next)
            previous?.let {File(root,it.getString("revision")).deleteRecursively()}
            return JSONObject(next.toString()).apply {remove("revision")}
        } catch(t:Throwable) {dir.deleteRecursively();throw t}
    }
    @Synchronized fun textPackage(content: String, resources: JSONObject?, expectedHash: String): Map<String,ByteArray> {
        val name=manifest(content).first;val old=entries.singleOrNull {it.getString("name")==name}
        require((old?.getString("hash") ?: "")==expectedHash && old?.optBoolean("builtin")!=true) {"版本冲突或内置 Skill"}
        val files=linkedMapOf<String,ByteArray>()
        if(old!=null) {val dir=File(root,old.getString("revision"));dir.walkTopDown().filter {it.isFile}.forEach {files[it.relativeTo(dir).invariantSeparatorsPath]=it.readBytes()}}
        resources?.keys()?.forEach {path->require(path!="SKILL.md");files[safePath(path)]=resources.getString(path).toByteArray()}
        files["SKILL.md"]=content.toByteArray()
        require(files.size<=128 && files.values.all {it.size<=FILE_LIMIT} && files.values.sumOf {it.size}<=PACKAGE_LIMIT)
        return files
    }
    @Synchronized fun setEnabled(name:String,enabled:Boolean,expectedHash:String?=null) {
        val e=entry(name);require(expectedHash==null || e.getString("hash")==expectedHash)
        commit(entries.map {if(it===e)JSONObject(it.toString()).put("enabled",enabled) else it})
    }
    @Synchronized fun delete(name:String,expectedHash:String) {
        val e=entry(name);require(!e.getBoolean("builtin") && e.getString("hash")==expectedHash)
        commit(entries.filter {it!==e});File(root,e.getString("revision")).deleteRecursively()
    }
    @Synchronized fun installBuiltin(content: String) {
        installBuiltin(mapOf("SKILL.md" to content.toByteArray()))
    }
    @Synchronized fun installBuiltin(files: Map<String,ByteArray>) {
        val name=manifest(utf8(files.getValue("SKILL.md"))).first
        val previous=entries.singleOrNull {it.getString("name")==name}
        if(previous==null)save(files,builtin=true)
        else if(previous.getBoolean("builtin")) {
            val hash=digest(files.toSortedMap().entries.joinToString("\n") {(path,bytes)->"$path:${digest(bytes)}"}.toByteArray())
            if(hash!=previous.getString("hash"))save(files,previous.getString("hash"),builtin=true)
        }
    }
    private fun commit(next: List<JSONObject>) {
        val tmp=File(root,"catalog.tmp")
        java.io.FileOutputStream(tmp).use {it.write(JSONArray(next).toString().toByteArray());it.fd.sync()}
        check(tmp.renameTo(index)) {"Skill 保存失败"};entries=next.toMutableList()
    }
}
