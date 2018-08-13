# BACnet DSLink

Version 2.0 of the BACnet DSLink

## License

GNU GPL

## Connection Guide

Use the `Add Connection` action to set up an IP or MSTP connection. This will create a BACnet local device which will be used to communicate with remote devices.

### Add IP Connection
 - `Name` - a name for the connection
 - `Subnet Mask` - the subnet mask of the network (e.g. `255.255.255.0`)
	 - Used with the local bind address to determine the broadcast IP
 - `Port` - port to be used for BACnet communication - 47808 is protocol standard
 - `Local Bind Address` - IP address of this machine on the network
 - `Local Network Number` - network number of the network
 - `Register As Foreign Device in BBMD` - Set this to true if it's a remote network behind a BBMD (DEPRECATED - see `Routers`)
 - `BBMD IPs With Network Number` - If the network is behind a BBMD, set this to the BBMD's address, in the format `host:port:network number` (e.g. `74.110.102.112:47808:0`) (DEPRECATED - see `Routers`)
 - `Timeout` - timeout for BACnet requests, in milliseconds
 - `Segment Timeout` - segment timeout for BACnet requests, in milliseconds
 - `Segment Window` - the proposed segment window size for sending BACnet requests
 - `Retries` - how many attempts to make to perform BACnet operations
 - `Local Device ID` - ID of this connection as a BACnet local device
	 - BACnet device IDs should be unique within the network
 - `Local Device Name` - a name for this local device
 - `Local Device Vendor` - vendor name property of this local device

### Add MSTP Connection
 - `Name` - a name for the connection
 - `Comm Port ID` - serial port to connect to
	 - The DSLink should automatically detect any available serial ports, allowing you to choose one from a drop-down menu
	 - If you don't see your serial port in the drop-down, try invoking the `scan for serial ports` action
	 - If no serial ports are found, you can enter the name of your serial port manually here
 - `Comm Port ID (Manual Entry)` - If the serial port you want to use is not in the drop-down, enter it here. Otherwise, leave this field blank.
	 - If this field is not blank, then its value will be used instead of the selection from the drop-down
 - `Baud Rate` - the baud rate of the serial connection 
 - `This Station ID` - address of this station in the MSTP network - must be between 0 and 127
 - `Frame Error Retry Count` - number of times to retry sending the MSTP token
 - `Max Info Frames` - the maximum number of info frames this station can send before it must pass on the MSTP token
 - `Local Network Number` - network number of the network
 - `Timeout` - timeout for BACnet requests, in milliseconds
 - `Segment Timeout` - segment timeout for BACnet requests, in milliseconds
 - `Segment Window` - the proposed segment window size for sending BACnet requests
 - `Retries` - how many attempts to make to perform BACnet operations
 - `Local Device ID` - ID of this connection as a BACnet local device
	 - BACnet device IDs should be unique within the network
 - `Local Device Name` - a name for this local device
 - `Local Device Vendor` - vendor name property of this local device

### Add devices
Now that you have a connection set up, its node should have a child called `Connection Status`. If the connection was successful, this should have a value of `Connected`.

Use the connection's `discover devices` action to trigger the connection to discover devices on the network. After this, you can use `Add All Discovered Devices` to add all devices that were discovered into the DSA tree, or use `Add Device` -> `From Discovered` to choose from a drop-down of discovered devices. You can also add a device by its instance number or address instead of discovering.

### Routers
If you have BACnet devices behind some sort of BACnet router, you will need to add the router to the `ROUTERS` node (under the connection node) so that the DSLink is able to communicate with those devices. Invoke the `add router` action:
 - `Network Number` - The number of the network that is behind the router; i.e. the network number of the devices you're trying to access
 - `IP` - The address of the router device
 - `Port` - The port of the router device
 - `Register as Foreign Device` - Set this to `true` if the router is a BBMD, to have the BACnet DSLink be registered in it as a foreign device
