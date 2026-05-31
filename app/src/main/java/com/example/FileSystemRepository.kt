package com.example

import android.os.Environment
import java.io.File
import java.io.FileOutputStream

class FileSystemRepository {

    fun getRootDirectory(): File {
        // Try accessing primary external storage, fall back to files dir
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/sdcard") // typical android mount
        }
    }

    fun listFiles(relativePath: String = ""): List<WormholeFileItem> {
        val root = getRootDirectory()
        val targetDir = if (relativePath.isEmpty()) root else File(root, relativePath)

        SystemLogRepository.log("FILE_SYS", "Accessing directory: ${targetDir.absolutePath}")

        if (!targetDir.exists()) {
            SystemLogRepository.log("FILE_SYS_ERR", "Directory does not exist: ${targetDir.absolutePath}")
            return emptyList()
        }
        if (!targetDir.isDirectory) {
            SystemLogRepository.log("FILE_SYS_ERR", "Path is not a directory: ${targetDir.absolutePath}")
            return emptyList()
        }

        val items = mutableListOf<WormholeFileItem>()
        // Always add parent directory option if we are not at root
        if (targetDir.absolutePath != root.absolutePath) {
            val parentRelative = targetDir.parentFile?.relativeToOrSelf(root)?.path ?: ""
            items.add(
                WormholeFileItem(
                    name = "..",
                    relativePath = parentRelative,
                    isDirectory = true,
                    size = 0L,
                    lastModified = 0L
                )
            )
        }

        try {
            val list = targetDir.listFiles()
            if (list != null) {
                // Directories first, then files
                val sortedList = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                for (file in sortedList) {
                    val relativeFile = file.relativeTo(root).path
                    items.add(
                        WormholeFileItem(
                            name = file.name,
                            relativePath = relativeFile,
                            isDirectory = file.isDirectory,
                            size = if (file.isDirectory) 0L else file.length(),
                            lastModified = file.lastModified()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            SystemLogRepository.log("FILE_SYS_ERR", "Failed listing files: ${e.message}")
        }

        return items
    }

    fun readFileBytes(relativePath: String): ByteArray? {
        val root = getRootDirectory()
        val file = File(root, relativePath)
        SystemLogRepository.log("FILE_SYS", "Reading file bytes: ${file.absolutePath}")
        return if (file.exists() && file.isFile) {
            try {
                file.readBytes()
            } catch (e: Exception) {
                SystemLogRepository.log("FILE_SYS_ERR", "Error reading file: ${e.message}")
                null
            }
        } else {
            SystemLogRepository.log("FILE_SYS_ERR", "File not found or is a directory: ${file.absolutePath}")
            null
        }
    }

    fun writeFileBytes(relativePath: String, data: ByteArray): Boolean {
        val root = getRootDirectory()
        val file = File(root, relativePath)
        SystemLogRepository.log("FILE_SYS", "Writing file bytes to: ${file.absolutePath}")
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { fos ->
                fos.write(data)
            }
            SystemLogRepository.log("FILE_SYS", "Successfully wrote ${data.size} bytes.")
            true
        } catch (e: Exception) {
            SystemLogRepository.log("FILE_SYS_ERR", "Failed writing file: ${e.message}")
            false
        }
    }

    fun deleteFile(relativePath: String): Boolean {
        val root = getRootDirectory()
        val file = File(root, relativePath)
        SystemLogRepository.log("FILE_SYS", "Deleting path: ${file.absolutePath}")
        return try {
            if (file.exists()) {
                val deleted = file.delete()
                SystemLogRepository.log("FILE_SYS", "Delete result: $deleted")
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            SystemLogRepository.log("FILE_SYS_ERR", "Failed deleting file: ${e.message}")
            false
        }
    }
}

data class WormholeFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)
