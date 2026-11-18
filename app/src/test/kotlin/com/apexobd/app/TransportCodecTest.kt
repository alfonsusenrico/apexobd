package com.apexobd.app

import com.apexobd.app.data.prefs.UserSettings
import com.apexobd.app.platform.BleElmGattCatalog
import com.apexobd.app.platform.BluetoothClassicObdTransport
import com.apexobd.app.platform.UsbChipFamily
import com.apexobd.app.platform.UsbSerialCodec
import com.apexobd.core.transport.ObdConnectionType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportCodecTest {

    @Test
    fun `BluetoothClassicObdTransport detects known OBD2 adapter names`() {
        assertTrue(BluetoothClassicObdTransport.isLikelyObdName("OBDII"))
        assertTrue(BluetoothClassicObdTransport.isLikelyObdName("Vgate iCar Pro BT3.0"))
        assertTrue(BluetoothClassicObdTransport.isLikelyObdName("konnwei-902"))
        assertFalse(BluetoothClassicObdTransport.isLikelyObdName("JBL Flip 6"))
        assertFalse(BluetoothClassicObdTransport.isLikelyObdName(null))
    }

    @Test
    fun `BleElmGattCatalog matches standard BLE ELM327 GATT UUID prefixes`() {
        assertTrue(BleElmGattCatalog.matchesPrefix("0000FFF0-0000-1000-8000-00805F9B34FB", "0000fff0"))
        assertTrue(BleElmGattCatalog.matchesPrefix("0000ffe1-0000-1000-8000-00805f9b34fb", "0000ffe1"))
        assertTrue(BleElmGattCatalog.matchesPrefix("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", "6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        assertEquals(5, BleElmGattCatalog.knownProfiles.size)
    }

    @Test
    fun `UsbSerialCodec detects chipset family and computes wire baud payloads`() {
        assertEquals(UsbChipFamily.FTDI, UsbSerialCodec.detectChipFamily(0x0403, 255))
        assertEquals(UsbChipFamily.CP210X, UsbSerialCodec.detectChipFamily(0x10C4, 255))
        assertEquals(UsbChipFamily.CH340, UsbSerialCodec.detectChipFamily(0x1A86, 255))
        assertEquals(UsbChipFamily.CDC_ACM, UsbSerialCodec.detectChipFamily(0x2341, 2))
        assertEquals(UsbChipFamily.GENERIC_BULK, UsbSerialCodec.detectChipFamily(0x9999, 255))

        // 38400 = 0x00009600 LE -> 0x00, 0x96, 0x00, 0x00
        assertArrayEquals(
            byteArrayOf(0x00, 0x96.toByte(), 0x00, 0x00, 0x00, 0x00, 0x08),
            UsbSerialCodec.cdcLineCodingBytes(38400)
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00),
            UsbSerialCodec.cp210xBaudBytes(115200)
        )
        assertEquals(78, UsbSerialCodec.ftdiBaudDivisorValue(38400))
    }

    @Test
    fun `UserSettings formats compact adapter header labels for each transport mode`() {
        val base = UserSettings(
            wifiHost = "192.168.0.10",
            wifiPort = 35000,
            btDeviceName = "OBDII",
            bleDeviceName = "Vgate iCar",
            usbBaudRate = 38400
        )
        assertEquals("Wi-Fi · 192.168.0.10", base.copy(connectionType = ObdConnectionType.WIFI_TCP).adapterChipLabel())
        assertEquals("BT · OBDII", base.copy(connectionType = ObdConnectionType.BLUETOOTH_CLASSIC).adapterChipLabel())
        assertEquals("BLE · Vgate iCar", base.copy(connectionType = ObdConnectionType.BLUETOOTH_LE).adapterChipLabel())
        assertEquals("USB · 38400", base.copy(connectionType = ObdConnectionType.USB_SERIAL).adapterChipLabel())
    }
}
