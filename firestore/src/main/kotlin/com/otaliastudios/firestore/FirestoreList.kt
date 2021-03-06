/*
 * Copyright (c) 2018 Otalia Studios. Author: Mattia Iavarone.
 */

package com.otaliastudios.firestore

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.Keep
import com.google.firebase.firestore.Exclude

/**
 * A list implementation. Delegates to a mutable list.
 *
 * The point of list is dirtyness.
 *
 * When an item is inserted, removed, or when a inner FirestoreMap/List is changed,
 * this list should be marked as dirty.
 */
@Keep
open class FirestoreList<T: Any> @JvmOverloads constructor(
        source: List<T>? = null
) : /* ObservableList<T>, MutableList<T> by data, */Iterable<T>, Parcelable {

    private val data: MutableList<T> = mutableListOf()

    @get:Exclude
    val size get() = data.size

    init {
        if (source != null) {
            mergeValues(source, false, "")
        }
    }

    override fun iterator(): Iterator<T> {
        return data.iterator()
    }

    private var isDirty = false

    @Suppress("UNCHECKED_CAST")
    internal fun flattenValues(map: MutableMap<String, Any?>, prefix: String, dirtyOnly: Boolean) {
        if (dirtyOnly && !isDirty()) return
        val list = mutableListOf<T>()
        forEach {
            if (it is FirestoreMap<*>) {
                val cleanMap = mutableMapOf<String, Any?>()
                it.flattenValues(cleanMap, "", dirtyOnly)
                list.add(cleanMap as T)
            } else if (it is FirestoreList<*>) {
                val cleanMap = mutableMapOf<String, Any?>()
                it.flattenValues(cleanMap, "", dirtyOnly)
                list.add(cleanMap as T)
            } else {
                list.add(it)
            }
        }
        map[prefix] = list
    }

    @Suppress("UNCHECKED_CAST")
    internal fun collectValues(dirtyOnly: Boolean): List<T> {
        if (dirtyOnly && !isDirty()) return listOf()
        val list = mutableListOf<T>()
        forEach {
            if (it is FirestoreMap<*>) {
                list.add(it.collectValues(dirtyOnly) as T)
            } else if (it is FirestoreList<*>) {
                list.add(it.collectValues(dirtyOnly) as T)
            } else {
                list.add(it)
            }
        }
        return list
    }

    internal fun isDirty(): Boolean {
        if (isDirty) return true
        for (it in this) {
            if (it is FirestoreList<*> && it.isDirty()) return true
            if (it is FirestoreMap<*> && it.isDirty()) return true
        }
        return false
    }

    internal fun clearDirt() {
        isDirty = false
        for (it in this) {
            if (it is FirestoreList<*>) it.clearDirt()
            if (it is FirestoreMap<*>) it.clearDirt()
        }
    }

    private fun <K> createFirestoreMap(): FirestoreMap<K> {
        val map = try { onCreateFirestoreMap<K>() } catch (e: Exception) {
            FirestoreMap<K>()
        }
        map.clearDirt()
        return map
    }

    private fun <K: Any> createFirestoreList(): FirestoreList<K> {
        val list = try { onCreateFirestoreList<K>() } catch (e: Exception) {
            FirestoreList<K>()
        }
        list.clearDirt()
        return list
    }

    protected open fun <K> onCreateFirestoreMap(): FirestoreMap<K> {
        val provider = FirestoreDocument.metadataProvider(this::class)
        return provider.createInnerType<FirestoreMap<K>>() ?: FirestoreMap()
    }

    protected open fun <K: Any> onCreateFirestoreList(): FirestoreList<K> {
        val provider = FirestoreDocument.metadataProvider(this::class)
        return provider.createInnerType<FirestoreList<K>>() ?: FirestoreList()
    }

    @Suppress("UNCHECKED_CAST")
    internal fun mergeValues(values: List<T>, checkChanges: Boolean, tag: String): Boolean {
        var changed = size != values.size
        val copy = if (checkChanges) data.toList() else listOf()
        data.clear()
        for (value in values) {
            if (value is Map<*, *> && value.keys.all { it is String }) {
                val child = createFirestoreMap<Any?>() as T
                data.add(child)
                child as FirestoreMap<Any?>
                value as Map<String, Any?>
                val childChanged = child.mergeValues(value, checkChanges && !changed, tag)
                changed = changed || childChanged
            } else if (value is List<*>) {
                val child = createFirestoreList<Any>() as T
                data.add(child)
                child as FirestoreList<Any>
                value as List<Any>
                val childChanged = child.mergeValues(value, checkChanges && !changed, tag)
                changed = changed || childChanged
            } else {
                if (checkChanges && !changed) {
                    val index = data.size
                    val itemChanged = index > copy.lastIndex || value != copy[index]
                    changed = changed || itemChanged
                }
                data.add(value)
            }
        }
        return checkChanges && changed
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is FirestoreList<*> &&
                other.data.size == data.size &&
                other.data.containsAll(data) &&
                other.isDirty == isDirty
    }

    override fun hashCode(): Int {
        var result = data.hashCode()
        result = 31 * result + isDirty.hashCode()
        return result
    }

    // Parcelable stuff.

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        val hashcode = hashCode()
        parcel.writeInt(hashcode)

        // Write class name
        FirestoreLogger.i { "List $hashcode: writing class ${this::class.java.name}" }
        parcel.writeString(this::class.java.name)

        // Write size and dirtiness
        FirestoreLogger.v { "List $hashcode: writing dirty $isDirty and size $size" }
        parcel.writeInt(if (isDirty) 1 else 0)
        parcel.writeInt(size)

        // Write actual data
        for (value in data) {
            FirestoreLogger.v { "List $hashcode: writing value $value" }
            FirestoreParcelers.write(parcel, value, hashcode.toString())
        }

        // Extra bundle
        val bundle = Bundle()
        onWriteToBundle(bundle)
        FirestoreLogger.v { "List $hashcode: writing extra bundle. Size is ${bundle.size()}" }
        parcel.writeBundle(bundle)
    }

    companion object {

        @Suppress("unused")
        @JvmField
        public val CREATOR = object : Parcelable.ClassLoaderCreator<FirestoreList<Any>> {

            override fun createFromParcel(source: Parcel): FirestoreList<Any> {
                // This should never be called by the framework.
                FirestoreLogger.e { "List: received call to createFromParcel without classLoader." }
                return createFromParcel(source, FirestoreList::class.java.classLoader!!)
            }

            override fun createFromParcel(parcel: Parcel, loader: ClassLoader): FirestoreList<Any> {
                val hashcode = parcel.readInt()
                // Read class name
                val klass = Class.forName(parcel.readString()!!)
                FirestoreLogger.i { "List $hashcode: read class ${klass.simpleName}" }
                @Suppress("UNCHECKED_CAST")
                val dataList = klass.newInstance() as FirestoreList<Any>

                // Read dirtyness and size
                dataList.isDirty = parcel.readInt() == 1
                val count = parcel.readInt()
                FirestoreLogger.v { "List $hashcode: read dirtyness ${dataList.isDirty} and size $count" }

                // Read actual data
                repeat(count) {
                    FirestoreLogger.v { "List $hashcode: reading value..." }
                    dataList.data.add(FirestoreParcelers.read(parcel, loader, hashcode.toString())!!)
                }

                // Extra bundle
                FirestoreLogger.v { "List $hashcode: reading extra bundle." }
                val bundle = parcel.readBundle(loader)!!
                FirestoreLogger.v { "List $hashcode: read extra bundle, size ${bundle.size()}" }
                dataList.onReadFromBundle(bundle)
                return dataList
            }

            override fun newArray(size: Int): Array<FirestoreList<Any>?> {
                return Array(size) { null }
            }
        }
    }

    protected open fun onWriteToBundle(bundle: Bundle) {}

    protected open fun onReadFromBundle(bundle: Bundle) {}

    // Dirtyness stuff.

    fun add(element: T): Boolean {
        data.add(element)
        isDirty = true
        return true
    }

    fun add(index: Int, element: T) {
        data.add(index, element)
        isDirty = true
    }

    fun remove(element: T): Boolean {
        val result = data.remove(element)
        isDirty = true
        return result
    }

    operator fun set(index: Int, element: T): T {
        val value = data.set(index, element)
        isDirty = true
        return value
    }

    // ObservableArrayList stuff.
    /*
    private var registry: ListChangeRegistry = ListChangeRegistry()

    override fun addOnListChangedCallback(callback: ObservableList.OnListChangedCallback<out ObservableList<T>>?) {
        registry.add(callback)
    }

    override fun removeOnListChangedCallback(callback: ObservableList.OnListChangedCallback<out ObservableList<T>>?) {
        registry.remove(callback)
    }

    override fun clear() {
        val oldSize = size
        data.clear()
        if (oldSize != 0) {
            registry.notifyRemoved(this, 0, oldSize)
            isDirty = true
        }
    }

    override fun add(element: T): Boolean {
        data.add(element)
        registry.notifyInserted(this, size - 1, 1)
        isDirty = true
        return true
    }

    override fun add(index: Int, element: T) {
        data.add(index, element)
        registry.notifyInserted(this, index, 1)
        isDirty = true
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val oldSize = size
        val added = data.addAll(elements)
        if (added) {
            registry.notifyInserted(this, oldSize, size - oldSize)
            isDirty = true
        }
        return added
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        val added = data.addAll(index, elements)
        if (added) {
            registry.notifyInserted(this, index, elements.size)
            isDirty = true
        }
        return added
    }

    override fun remove(element: T): Boolean {
        val index = indexOf(element)
        if (index >= 0) {
            removeAt(index)
            return true
        } else {
            return false
        }
    }

    override fun removeAt(index: Int): T {
        val value = data.removeAt(index)
        registry.notifyRemoved(this, index, 1)
        isDirty = true
        return value
    }

    override fun set(index: Int, element: T): T {
        val value = data.set(index, element)
        registry.notifyChanged(this, index, 1)
        isDirty = true
        return value
    } */
}