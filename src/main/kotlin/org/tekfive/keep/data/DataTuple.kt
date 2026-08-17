package org.tekfive.keep.data

/** Read/query tuple base for the existing Long primary-key strategy. */
abstract class DataTuple<D : Data>(
    name: String,
    managedColumns: Set<String> = emptySet(),
) : TypedDataTuple<Long, D>(name, managedColumns)
