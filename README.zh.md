# Ros-IPSet

本项目的想法源自于JorDNS项目，修改的过程中完全重构了，所以基本已经不再保留有原项目的代码。
但是一些jrodns的名字保留了下来，算是对原作者的致敬！


## ipset

本项目的作用是向RouterOS中添加特定的地址列表，类似于Linux中ipset的作用。

ROS本身没有ipset的功能，这可能带给习惯于使用ipset进行地址列表管理的同学一些不方便。

Ros-IPSet以DNS服务的形式工作，并将DNS请求中符合blocklist列表的ipv4地址放入到ROS的特定address-list中。
这样在ROS中就可以使用这个地址列表进行分流、标记等常见的操作。

具体处理逻辑是：将DNS解析请求转发到romote服务器上；如果请求解析的域名在blocklist中，将DNS解析返回的ip地址
加到ROS对应的address-list列表中；但whitelist列表中的域名不会被加入。
例如：可以将google相关域名放在blocklist里，同时将中国地区可以直连的google网站放到whitelist里面，这样就
只有中国区不能直接访问的google服务会加入到address-list中。

如果remote服务器无法正常访问，Ros-IPSet会试着从后备DNS服务器获得地址解析请求，但其结果不会放到
address-list里。

address-list所加入的记录，有效期均设置为24小时，到期自动被清除。
由于address-list中仅包括访问时用到的域名所对应的ip地址，长度有限，对于ROS的工作效率影响也非常小。

如果手工清除了address-list中的记录，需要同时重启Ros-IPSet服务，确保Ros-IPSet中的缓存记录与ROS内的
记录数据同步。

所有选项在jrodns.properties中间设定，相当简单，在中文说明中不再另行解释。

## AdBlock

本项目还支持AdBlock，原理是将adblock列表中的域名全部解析为一个缺省的地址（默认为224.0.0.1），这样会间接使得
广告访问失效。

## 有用的资源

[blocklist file](https://github.com/Loyalsoldier/v2ray-rules-dat) 

[docker形式](https://hub.docker.com/r/whitemay/ros-ipset)
