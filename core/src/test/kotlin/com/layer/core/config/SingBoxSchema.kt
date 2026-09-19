package com.layer.core.config

import com.networknt.schema.Error
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion

/**
 * Stock sing-box 1.14.1 JSON Schema. XHTTP configs are out of scope:
 * that transport exists only in our libbox patch.
 */
object SingBoxSchema {
    private val schema by lazy {
        val stream = checkNotNull(
            javaClass.getResourceAsStream("/sing-box/schema-1.14.1.json"),
        ) { "missing sing-box 1.14.1 schema" }
        stream.use { input ->
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(input)
        }
    }

    fun assertValid(json: String): List<Error> {
        return schema.validate(json, InputFormat.JSON)
    }
}
