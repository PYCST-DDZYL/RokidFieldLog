using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

// Read-only hub queries. No RESET, SET_CONFIGURATION, string/serial queries,
// driver installation, registry writes, or device control commands.
public static class FieldLogUsbReader
{
    [StructLayout(LayoutKind.Sequential)]
    struct InterfaceData {
        public uint cbSize;
        public Guid interfaceClassGuid;
        public uint flags;
        public IntPtr reserved;
    }
    [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr SetupDiGetClassDevs(ref Guid guid, IntPtr enumerator, IntPtr parent, uint flags);
    [DllImport("setupapi.dll", SetLastError=true)]
    static extern bool SetupDiEnumDeviceInterfaces(IntPtr set, IntPtr device, ref Guid guid, uint index, ref InterfaceData data);
    [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr set, ref InterfaceData data, IntPtr detail, uint size, out uint required, IntPtr device);
    [DllImport("setupapi.dll")]
    static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr CreateFile(string name, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool DeviceIoControl(IntPtr device, uint code, byte[] input, int inputSize, byte[] output, int outputSize, out int returned, IntPtr overlapped);
    [DllImport("kernel32.dll")]
    static extern bool CloseHandle(IntPtr handle);

    public static string[] Read()
    {
        var messages = new List<string>();
        Guid hubGuid = new Guid("f18a0e88-c30c-11d0-8815-00a0c906bed8");
        IntPtr set = SetupDiGetClassDevs(ref hubGuid, IntPtr.Zero, IntPtr.Zero, 0x12);
        if (set == new IntPtr(-1)) throw new InvalidOperationException("Cannot enumerate USB hubs.");
        int hubsRead = 0, samsungFound = 0;
        try {
            for (uint index=0; index<128; index++) {
                var data = new InterfaceData { cbSize=(uint)Marshal.SizeOf(typeof(InterfaceData)) };
                if (!SetupDiEnumDeviceInterfaces(set, IntPtr.Zero, ref hubGuid, index, ref data)) break;
                uint required;
                SetupDiGetDeviceInterfaceDetail(set, ref data, IntPtr.Zero, 0, out required, IntPtr.Zero);
                if (required < 8 || required > 65536) continue;
                IntPtr detail = Marshal.AllocHGlobal((int)required);
                try {
                    Marshal.WriteInt32(detail, IntPtr.Size == 8 ? 8 : 6);
                    if (!SetupDiGetDeviceInterfaceDetail(set, ref data, detail, required, out required, IntPtr.Zero)) continue;
                    string path = Marshal.PtrToStringUni(IntPtr.Add(detail,4));
                    // Zero desired access suffices for FILE_ANY_ACCESS GET queries.
                    IntPtr hub = CreateFile(path, 0, 3, IntPtr.Zero, 3, 0, IntPtr.Zero);
                    if (hub == new IntPtr(-1)) { messages.Add("Hub read unavailable; Win32 error " + Marshal.GetLastWin32Error()); continue; }
                    try {
                        var node = new byte[1024]; int returned;
                        if (!DeviceIoControl(hub, 0x220408, node, node.Length, node, node.Length, out returned, IntPtr.Zero) || returned < 7) continue;
                        hubsRead++;
                        int ports = node[6];
                        for (int port=1; port<=ports; port++) {
                            var connection = new byte[4096];
                            Array.Copy(BitConverter.GetBytes(port), connection, 4);
                            if (!DeviceIoControl(hub, 0x220448, connection, connection.Length, connection, connection.Length, out returned, IntPtr.Zero) || returned < 22) continue;
                            if (connection[4] != 18 || connection[5] != 1 || BitConverter.ToUInt16(connection,12) != 0x04e8) continue;
                            samsungFound++;
                            int product = BitConverter.ToUInt16(connection,14);
                            var request = new byte[12+4096];
                            Array.Copy(BitConverter.GetBytes(port), request, 4);
                            request[4]=0x80; request[5]=6; request[7]=2; // GET CONFIGURATION descriptor, index zero.
                            request[10]=0; request[11]=16; // 4096 bytes; no strings or serials.
                            if (!DeviceIoControl(hub, 0x220410, request, request.Length, request, request.Length, out returned, IntPtr.Zero) || returned < 21) {
                                messages.Add("Samsung configuration read failed; Win32 error " + Marshal.GetLastWin32Error()); continue;
                            }
                            int total = BitConverter.ToUInt16(request,14);
                            int end = Math.Min(returned,12+total);
                            messages.Add(String.Format("Samsung VID=04E8 PID={0:X4}; configuration interfaces={1}; descriptor bytes={2}",product,request[16],total));
                            bool adb=false;
                            for (int offset=12; offset+2<=end;) {
                                int length=request[offset];
                                if (length < 2 || offset+length>end) break;
                                if (request[offset+1]==4 && length>=9) {
                                    int cls=request[offset+5], sub=request[offset+6], protocol=request[offset+7];
                                    messages.Add(String.Format("Interface {0}, alternate {1}: class {2:X2}, subclass {3:X2}, protocol {4:X2}",request[offset+2],request[offset+3],cls,sub,protocol));
                                    adb |= cls==0xff && sub==0x42 && protocol==1;
                                }
                                offset += length;
                            }
                            messages.Add("ADB FF/42/01 descriptor present: " + adb);
                        }
                    } finally { CloseHandle(hub); }
                } finally { Marshal.FreeHGlobal(detail); }
            }
        } finally { SetupDiDestroyDeviceInfoList(set); }
        messages.Add("Hubs read: " + hubsRead + "; Samsung devices queried: " + samsungFound);
        return messages.ToArray();
    }
}
