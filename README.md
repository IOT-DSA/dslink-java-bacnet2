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
 - `Use Wildcard Address for Binding` - Whether to bind the UDP socket to the wildcard address (On many systems, this is required in order to receive broadcast messages)
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

### Writing to properties
By default, all properties that are required to be writable according to the BACnet protocol can be written to using the `@set` action. However, all properties have an `edit` action that allows you to make any property writable (from the DSA side; whether the write succeeds will still depend on the BACnet device).

Complex (non-primitive) BACnet data types are represented in DSA as either JSON arrays or JSON maps. Writing to properties with such types (e.g. weekly-schedule) can be a little tricky in DGLux, so here is a dataflow block that can be helpful:

    {"@type":"dataflow","!label":"editJson","editJson":{"@type":"dfsymbol","symbol":"editJson","!ps":["path","currentValue","newValue","invoke","error"],"!x":118,"!y":78},"@symbols":{"@df":{"editJson":{"@type":"dfcontent","set1":{"@type":"invokeAction","path":["@parent.string9.value"],"action":"@set","!ps":["invoke","path","action","error","value"],"!x":451,"!y":443,"!actProps":[["value","array",null]],"value":["@parent.script.output"],"!w":185,"invoke":["@parent.@params.invoke"]},"string9":{"@type":"string","value":["@parent.@params.path"],"!ps":["value"],"!x":133,"!y":271,"!label":"path","!w":85},"script":{"@type":"script","!ps":["invoke","script","output","print"],"!x":263,"!y":467,"invoke":["@parent.string8.value"],"script":"@.invoke"},"string8":{"@type":"string","value":["@parent.@params.newValue"],"!ps":["value"],"!x":122,"!y":511,"!label":"newValue"},"weekly_schedule1":{"@type":"loadValue","path":["@parent.string9.value"],"!ps":["path","value","formatted"],"!x":357,"!y":291},"string11":{"@type":"string","value":["@parent.weekly_schedule1.value"],"!ps":["value"],"!x":538,"!y":363,"!label":"currentValue"},"!df":true,"@params":{"!var":[{"n":"path","t":"textarea"},{"n":"currentValue","t":"textarea"},{"n":"newValue","t":"textarea"},{"n":"invoke","t":"trigger"},{"n":"error","t":"string"}],"newValue":"","path":"","currentValue":["@parent.string11.value"],"error":["@parent.set1.error"]}}}},"@ver":7593}

In the dataflow DSLink, import the above code. Then you can use the `editJson` symbol to write to complex-typed BACnet properties. First drag the path of the desired property into the `path` field. This will cause the current value of the property to show up in the `currentValue` field as a JSON string, allowing you to copy/paste and make changes to it. Once you've modified the property as necessary, paste the new JSON string into the `newValue` field and click `invoke` to write the new value to the property.


### Routers
If you have BACnet devices behind some sort of BACnet router, you will need to add the router to the `ROUTERS` node (under the connection node) so that the DSLink is able to communicate with those devices. Invoke the `add router` action:
 - `Network Number` - The number of the network that is behind the router; i.e. the network number of the devices you're trying to access
 - `IP` - The address of the router device
 - `Port` - The port of the router device
 - `Register as Foreign Device` - Set this to `true` if the router is a BBMD, to have the BACnet DSLink be registered in it as a foreign device
 
### Troubleshooting

   - Verify that the subnet mask is set to the subnet mask of the network containing the machine running the DSLink and the BACnet device(s) (This information can usually be found by running ipconfig or ifconfig from command line)
   - Verify that the local bind address is set to the IP (in that network) of  *the machine running the BACnet DSLink* 
   - Are some or all of the BACnet devices you're ttrying to connect to behind some sort of BACnet router (this could be a BBMD, but not necessarily). If yes, ensure that the router is added to the ROUTERS node as described above.
   - Connect to the devices with some third party tool, such as YABE (https://sourceforge.net/projects/yetanotherbacnetexplorer/) to verify that they communicate properly. If YABE (or other tool) is unable to talk to the devices, then the problem is with setup, not the DSLink.

