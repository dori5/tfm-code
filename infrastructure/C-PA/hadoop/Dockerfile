FROM debian:jessie

# Java

ENV JAVA_HOME		/usr/bin/java

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        openjdk-7-jdk \
    && rm -rf /var/lib/apt/lists/*

# Hadoop

ENV HADOOP_VERSION	2.7.3
ENV HADOOP_HOME		/usr/local/hadoop
ENV HADOOP_OPTS		-Djava.library.path=/usr/local/hadoop/lib/native
ENV PATH		    $PATH:$HADOOP_HOME/bin:$HADOOP_HOME/sbin

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        libsnappy1 \
        libssl-dev \
        libzip2 \
        wget \
    && wget http://archive.apache.org/dist/hadoop/core/hadoop-$HADOOP_VERSION/hadoop-$HADOOP_VERSION.tar.gz \
    && apt-get remove -y wget \
    && rm -rf /var/lib/apt/lists/* \
    && tar -zxf /hadoop-$HADOOP_VERSION.tar.gz \
    && rm /hadoop-$HADOOP_VERSION.tar.gz \
    && mv hadoop-$HADOOP_VERSION /usr/local/hadoop \
    && mkdir -p /usr/local/hadoop/logs

# add Hadoop config

COPY conf $HADOOP_HOME/etc/hadoop/

ENV JAVA_HOME       /usr

# Git

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        git \
    && rm -rf /var/lib/apt/lists/*

# format HDFS

RUN mkdir -p /hdfs/data /hdfs/name /hdfs/secondary \
    && hdfs namenode -format

VOLUME /hdfs

# script for node startup

ADD start-hadoop.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/start-hadoop.sh \
    && chmod +x /usr/local/hadoop/etc/hadoop/hadoop-env.sh

# HDFS ports
EXPOSE 50010 50020 50070 50075 50090 8020 9000 9001

# MapReduce UI
EXPOSE 19888

# YARN ports
EXPOSE 8025 8030 8031 8032 8033 8040 8042 8088

ENTRYPOINT ["/usr/local/bin/start-hadoop.sh"]
