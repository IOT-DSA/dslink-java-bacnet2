#BACnet DSLink

##License

GNU GPL

##Troubleshooting

If device discovery doesn't work on IP, you most likely need to change either the local bind address or the broadcast address. First try setting the local bind address to your ip address. The default, 0.0.0.0, will work in some, but not all cases. For more information on broadcast addresses, consult https://en.wikipedia.org/wiki/Broadcast_address. Once again, the default, 255.255.255.255, will often work. Usually you will need to either set the local bind address to your ip, or set the broadcast address as described by the wikipedia article.
