web: java $JAVA_OPTS -Ddw.server.applicationConnectors[0].port=$PORT -Ddw.twilio.accountId=$TWILIO_ACCOUNT_SID -Ddw.twilio.accountToken=$TWILIO_ACCOUNT_TOKEN -Ddw.twilio.numbers[0]=$TWILIO_NUMBER -Ddw.twilio.localDomain=$TWILIO_DOMAIN -Ddw.s3.accelerate=$AWS_USE_ACCELERATE -Ddw.s3.accessKey=$AWS_ACCESS_KEY -Ddw.s3.accessSecret=$AWS_SECRET_KEY -Ddw.s3.attachmentsBucket=$AWS_ATTACHMENTS_BUCKET -Ddw.cache.url=$REDIS_URL -Ddw.directory.url=$REDIS_URL -Ddw.apn.pushCertificate="$APN_PUSH_CERTIFICATE" -Ddw.apn.pushKey="$APN_PUSH_KEY" -Ddw.apn.sandbox=$APN_SANDBOX -Ddw.gcm.apiKey=$GCM_API_KEY -Ddw.gcm.senderId=$GCM_SENDER_ID -Ddw.database.driverClass=org.postgresql.Driver -Ddw.database.user=`echo $DATABASE_URL | awk -F'://' {'print $2'} | awk -F':' {'print $1'}` -Ddw.database.password=`echo $DATABASE_URL | awk -F'://' {'print $2'} | awk -F':' {'print $2'} | awk -F'@' {'print $1'}` -Ddw.database.url=jdbc:postgresql://`echo $DATABASE_URL | awk -F'@' {'print $2'}` -Ddw.messageStore.driverClass=org.postgresql.Driver -Ddw.messageStore.user=`echo $MESSAGESTORE_URL | awk -F'://' {'print $2'} | awk -F':' {'print $1'}` -Ddw.messageStore.password=`echo $MESSAGESTORE_URL | awk -F'://' {'print $2'} | awk -F':' {'print $2'} | awk -F'@' {'print $1'}` -Ddw.messageStore.url=jdbc:postgresql://`echo $MESSAGESTORE_URL | awk -F'@' {'print $2'}` -Ddw.turn.secret=$TURN_SECRET -Ddw.turn.uris[0]=$TURN_URIS_0 -jar target/TextSecureServer-1.65.jar server config/$STAGE.yml
