package org.jellyfin.androidtv.offline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.offline.data.StorageSelector
import org.jellyfin.androidtv.offline.data.StorageSelector.Volume

class StorageSelectorTests : FunSpec({
	val internal = Volume("/storage/emulated/0/files", removable = false, freeBytes = 50_000)
	val sd = Volume("/storage/1234-ABCD/files", removable = true, freeBytes = 10_000)
	val usb = Volume("/storage/5678-EF01/files", removable = true, freeBytes = 90_000)

	test("auto prefers removable storage even with less free space") {
		StorageSelector.select(StorageSelector.AUTO, listOf(internal, sd)) shouldBe sd
	}

	test("auto picks the removable volume with the most free space") {
		StorageSelector.select(StorageSelector.AUTO, listOf(internal, sd, usb)) shouldBe usb
	}

	test("auto falls back to internal storage without removable storage") {
		StorageSelector.select(StorageSelector.AUTO, listOf(internal)) shouldBe internal
	}

	test("a chosen volume is used when mounted") {
		StorageSelector.select(internal.path, listOf(internal, sd)) shouldBe internal
		StorageSelector.select(sd.path, listOf(internal, sd)) shouldBe sd
	}

	test("a chosen volume that is not mounted never falls back to internal storage") {
		StorageSelector.select(sd.path, listOf(internal)) shouldBe null
	}

	test("nothing mounted") {
		StorageSelector.select(StorageSelector.AUTO, emptyList()) shouldBe null
	}
})
