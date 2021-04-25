#!/bin/bash


if [ -z $EUREKA_PORT ]; then
  export EUREKA_PORT=20303
fi
export SERVER_CLASS=com.webank.wedatasphere.linkis.eureka.SpringCloudEurekaApplication

cd `dirname $0`
cd ..
HOME=`pwd`

export SERVER_CONF_PATH=$HOME/conf
export SERVER_LIB=$HOME/lib
export SERVER_LOG_PATH=$HOME/logs

if [ ! -w "$SERVER_LOG_PATH" ] ; then
  mkdir -p "$SERVER_LOG_PATH"
fi

export SERVER_HEAP_SIZE="1G"

if test -z "$SERVER_JAVA_OPTS"
then
  export SERVER_JAVA_OPTS=" -Xmx$SERVER_HEAP_SIZE -XX:+UseG1GC -Xloggc:$SERVER_LOG_PATH/linkis-gc.log"
fi

export SERVER_CLASS_PATH=$SERVER_CONF_PATH:$SERVER_LIB/*

## set spring args
if [ "$EUREKA_PORT" != "" ]; then
  SPRING_ARGS="--server.port=$EUREKA_PORT"
fi

if [ "$EUREKA_URL" != "" ]; then
  SPRING_ARGS="$SPRING_ARGS --eureka.client.serviceUrl.defaultZone=$EUREKA_URL"
fi

if [ "$$EUREKA_PREFER_IP" == "true" ]; then
  SPRING_ARGS="$SPRING_ARGS --eureka.instance.preferIpAddress=true "
else
  SPRING_ARGS="$SPRING_ARGS --eureka.instance.hostname=$EUREKA_HOSTNAME"
fi

SPRING_ARGS="$SPRING_ARGS --spring.profiles.active=eureka"

java $SERVER_JAVA_OPTS -cp $SERVER_CLASS_PATH $SERVER_CLASS $SPRING_ARGS 2>&1 > $SERVER_LOG_PATH/linkis.out