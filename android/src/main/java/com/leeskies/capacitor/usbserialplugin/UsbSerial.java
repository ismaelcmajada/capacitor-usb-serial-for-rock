package com.ismaelcmajada.capacitor.usbserialplugin;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UsbSerial {
    private static final String TAG = "UsbSerial";

    private final Context context;
    private final UsbManager manager;

    /** Puerto + conexión nativa para poder cerrar todo correctamente */
    static class PortBundle {
        final UsbSerialPort port;
        final UsbDeviceConnection conn;
        PortBundle(UsbSerialPort p, UsbDeviceConnection c) { this.port = p; this.conn = c; }
    }

    private final Map<String, PortBundle> active = new HashMap<>();

    private static String toHex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(String.format("%02X ", b[i]));
        return sb.toString().trim();
    }

    private String generatePortKey(UsbDevice device) {
        return device.getDeviceName() + "_" + device.getDeviceId();
    }

    public UsbSerial(Context context) {
        this.context = context;
        this.manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public List<JSObject> getDeviceConnections() {
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        List<JSObject> deviceList = new ArrayList<>();

        for (UsbSerialDriver driver : availableDrivers) {
            UsbDevice device = driver.getDevice();
            JSObject deviceInfo = new JSObject();
            deviceInfo.put("deviceKey", generatePortKey(device));
            deviceInfo.put("deviceId", device.getDeviceId());
            deviceInfo.put("productId", device.getProductId());
            deviceInfo.put("vendorId", device.getVendorId());
            deviceInfo.put("deviceName", device.getDeviceName());
            deviceList.add(deviceInfo);
        }
        return deviceList;
    }

    public void openConnection(PluginCall call) {
        int deviceId;
        try {
            deviceId = call.getInt("deviceId");
        } catch (NullPointerException e) {
            call.reject("DeviceId cannot be null");
            return;
        }

        for (UsbSerialDriver driver : UsbSerialProber.getDefaultProber().findAllDrivers(manager)) {
            if (driver.getDevice().getDeviceId() == deviceId) {
                UsbDevice device = driver.getDevice();
                if (!manager.hasPermission(device)) {
                    requestUsbPermission(device, call);
                    return;
                }
                proceedWithConnection(driver, device, call);
                return;
            }
        }
        call.reject("Device not found");
    }

    private void requestUsbPermission(UsbDevice device, PluginCall call) {
        String appName = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
        String ACTION_USB_PERMISSION = appName + ".USB_PERMISSION";

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent permissionIntent =
                PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        BroadcastReceiver usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (usbDevice != null) {
                                UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(usbDevice);
                                proceedWithConnection(driver, usbDevice, call);
                            } else {
                                call.reject("USB device was null after granting permission");
                            }
                        } else {
                            call.reject("USB permission denied");
                        }
                    }
                    try {
                        ctx.unregisterReceiver(this);
                    } catch (IllegalArgumentException ignore) { }
                }
            }
        };
        context.registerReceiver(usbReceiver, filter);
        manager.requestPermission(device, permissionIntent);
    }

    private void proceedWithConnection(UsbSerialDriver driver, UsbDevice device, PluginCall call) {
        int baudRate = call.getInt("baudRate", Const.DEFAULT_BAUD_RATE);
        int dataBits  = call.getInt("dataBits",  Const.DEFAULT_DATA_BITS);
        int stopBits  = call.getInt("stopBits",  Const.DEFAULT_STOP_BITS);

        // Paridad: puede venir como string ("none"/"even"/...) o número (0..4). Soporta ambas.
        int parity = Const.DEFAULT_PARITY;
        if (call.hasOption("parity")) {
            try {
                String parityKey = call.getString("parity");
                if (Const.PARITY.containsKey(parityKey)) {
                    parity = Const.PARITY.get(parityKey);
                }
            } catch (Exception ignored) {
                parity = call.getInt("parity", Const.DEFAULT_PARITY);
            }
        }

        if (!Const.STOP_BITS.containsKey(stopBits)) {
            call.reject("Invalid value for stopBits: " + stopBits);
            return;
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            call.reject("Failed to open device connection");
            return;
        }

        UsbSerialPort port = driver.getPorts().get(0);
        try {
            port.open(connection);
            port.setParameters(baudRate, dataBits, stopBits, parity);
            port.setDTR(true);
            port.setRTS(true);
            port.purgeHwBuffers(true, true);

            Log.d(TAG, "OPEN ok @ " + baudRate + " " + dataBits + "N" + stopBits + " DTR/RTS=ON");

            String key = generatePortKey(device);
            active.put(key, new PortBundle(port, connection));
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "open/init failed", e);
            try { port.close(); } catch (Exception ignore) {}
            try { connection.close(); } catch (Exception ignore) {}
            call.reject("Failed to initialize connection with selected device: " + e.getMessage());
        }
    }

    public void endConnection(PluginCall call) {
        String portKey = call.getString("key");
        if (portKey == null || !active.containsKey(portKey)) {
            call.reject("Invalid port key");
            return;
        }
        PortBundle pb = active.get(portKey);
        try {
            try { pb.port.purgeHwBuffers(true, true); } catch (Exception ignore) {}
            try { pb.port.setDTR(false); pb.port.setRTS(false); } catch (Exception ignore) {}
            try { pb.port.close(); } catch (Exception ignore) {}
            try { pb.conn.close(); } catch (Exception ignore) {}
            active.remove(portKey);
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to close port: " + e.getMessage());
        }
    }

    public void endConnections(PluginCall call) {
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, PortBundle> entry : active.entrySet()) {
            PortBundle pb = entry.getValue();
            try { pb.port.purgeHwBuffers(true, true); } catch (Exception ignore) {}
            try { pb.port.setDTR(false); pb.port.setRTS(false); } catch (Exception ignore) {}
            try { pb.port.close(); } catch (Exception e) {
                errors.add("Failed to close port " + entry.getKey() + ": " + e.getMessage());
            }
            try { pb.conn.close(); } catch (Exception e) {
                errors.add("Failed to close conn " + entry.getKey() + ": " + e.getMessage());
            }
        }
        active.clear();
        if (errors.isEmpty()) {
            call.resolve();
        } else {
            call.reject("Errors occurred while closing ports: " + String.join(", ", errors));
        }
    }

    public void endConnections(PluginCall call, List<String> keys) {
        List<String> errors = new ArrayList<>();
        for (String key : keys) {
            PortBundle pb = active.get(key);
            if (pb != null) {
                try { pb.port.purgeHwBuffers(true, true); } catch (Exception ignore) {}
                try { pb.port.setDTR(false); pb.port.setRTS(false); } catch (Exception ignore) {}
                try { pb.port.close(); } catch (Exception e) {
                    errors.add("Failed to close port " + key + ": " + e.getMessage());
                }
                try { pb.conn.close(); } catch (Exception e) {
                    errors.add("Failed to close conn " + key + ": " + e.getMessage());
                }
                active.remove(key);
            } else {
                errors.add("Port not found: " + key);
            }
        }
        if (errors.isEmpty()) {
            call.resolve();
        } else {
            call.reject("Errors occurred while closing ports: " + String.join(", ", errors));
        }
    }

    public void write(PluginCall call) {
        String portKey = call.getString("key");
        String message = call.getString("message");
        PortBundle pb = active.get(portKey);
        if (pb == null) {
            call.reject("Specified port not found: " + portKey);
            return;
        }

        try {
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            pb.port.write(messageBytes, Const.WRITE_WAIT_MILLIS);
            Log.d(TAG, "WRITE " + messageBytes.length + "B: " + toHex(messageBytes, messageBytes.length));

            JSObject result = new JSObject();
            result.put("bytesWritten", messageBytes.length);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Communication with port failed: " + e.getMessage());
        }
    }

    public void read(PluginCall call) {
        String portKey = call.getString("key");
        PortBundle pb = active.get(portKey);
        if (pb == null) {
            call.reject("Specified port not found");
            return;
        }

        try {
            byte[] buf = new byte[4096];
            int total = 0;
            long end = System.currentTimeMillis() + 1500; // ventana 1.5 s
            StringBuilder out = new StringBuilder();

            while (System.currentTimeMillis() < end) {
                int n = pb.port.read(buf, 100); // trozos de 100 ms
                if (n > 0) {
                    total += n;
                    Log.d(TAG, "READ chunk n=" + n + " HEX=" + toHex(buf, n));
                    out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (n < buf.length) break; // heurística: probablemente no hay más
                }
            }

            JSObject result = new JSObject();
            result.put("data", out.toString().trim());
            result.put("bytesRead", total);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to read data: " + e.getMessage());
        }
    }

    public void getActivePorts(PluginCall call) {
        JSObject result = new JSObject();
        JSArray keysArray = new JSArray();
        for (String key : active.keySet()) {
            keysArray.put(key);
        }
        result.put("ports", keysArray);
        call.resolve(result);
    }
}
