#! /bin/bash

function stop_services() {
	echo "Stopping Hadoop $1 services..."

	if [ "$1" = 'master' ]; then
		hadoop-daemon.sh --config $HADOOP_HOME/etc/hadoop --script hdfs stop namenode
		yarn-daemon.sh --config $HADOOP_HOME/etc/hadoop start proxyserver
	elif [ "$1" = 'worker' ]; then 
		hadoop-daemon.sh --config $HADOOP_HOME/etc/hadoop --script hdfs stop datanode
	fi
}

trap "stop_services" SIGHUP SIGINT EXIT SIGKILL SIGTERM

$HADOOP_HOME/etc/hadoop/hadoop-env.sh

echo "Starting Hadoop $1 services..."

if [ "$1" = 'master' ]; then
	hadoop-daemon.sh --config $HADOOP_HOME/etc/hadoop --script hdfs start namenode
	git clone https://github.com/dori5/tfm-data.git /home/downloads
	cd /usr/local/hadoop
	bin/hdfs dfsadmin -safemode leave
	bin/hdfs dfs -mkdir /data
	bin/hdfs dfs -put /home/downloads/original_dataset.txt /data
	yarn="yarn resourcemanager"
elif [ "$1" = 'worker' ]; then 
	hadoop-daemon.sh --config $HADOOP_HOME/etc/hadoop --script hdfs start datanode
	yarn="yarn nodemanager"
fi

echo "Hadoop $1 running. [Enter] or 'docker stop ...' to quit"

exec $yarn &  wait

	 
