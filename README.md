# ros-ipset (Original from Jrodns)
It is a tool original by actck. It is useful for me or any linux/ipset person using RouterOS.
I fork it to add some other minor functions, cause it was unactived for 2 years. 

But... I actually refacted the whole project. So, enjoy.

Also I will do a docker job, and put it on github. 

## some useful links

[中文说明](README.zh.md)

[docker format](https://hub.docker.com/r/whitemay/ros-ipset)

[blacklist file](https://github.com/Loyalsoldier/v2ray-rules-dat) 

# below is the origin readme

A dns proxy tool to help implement ipset for routeros.

In normal, people using dnsmasq+iptables+ipset to do that thing you know.
But if you are using a hw-router like routerboard(routeros), you can't install external
 service or access that "internal-iptables".
 
This just a dns proxy tool, you still need a trusted dns resource.

# how it working
1. listen dns query request
2. forward query to the top dns server
3. got the dns answer and put it into routeros firewall using routeros' api.

# prepare and run
1. java env.
2. Compile and package with mvn. An executeable jar file named jrodns-exec.jar
will be generating.
3. Put a config file same path with the jar. 
Config file name must be "jrodns.properties".
4. run with command "java -jar jrodns-exec.jar".
5. setting packet route rule in routeros's firewall.
6. change client's dns setting.

# config

|key |require|default|desc|
|:---|  :---:|   :---: |:---|
|gfwlistPath|Yes| |gfwlist files path, seperated by comma. Value could be file name or absolute path
|whitelistPath|No| |white list files, exception from gfwlist.
|adblockPath|No| |ad-block list files. domains will be resolve to block-address
|blockAddress|No|224.0.0.1|the address for domains in ad-block list 
|rosIp|Yes| | ros server ip
|rosUser|Yes| | ros router login username
|rosPwd|Yes| | ros router login password
|rosFwadrKey|Yes| | address-list key to set in ros
|rosIdle|No|30| ros api-connection check delay
|localPort|No|53|local port for client dns query request
|remote|Yes| |remote dns server for dns iterator request
|remotePort|Yes|53| remote dns server port for dns iterator request
|maxThread|No|10|server worker count

