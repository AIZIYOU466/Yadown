package com.yadown.core.io

import java.io.RandomAccessFile

interface FileStore {
    fun <T> withFile(path: String, block: (RandomAccessFile) -> T): T
}

class RandomAccessFileStore : FileStore {
    override fun <T> withFile(path: String, block: (RandomAccessFile) -> T): T =
        RandomAccessFile(path, "rw").use(block)
}
