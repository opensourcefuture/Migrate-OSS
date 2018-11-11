#!sbin/sh

# PARAMETERS: packageName destination apkPath apkName dataParentPath dataName busyboxBinaryPath isPermission
#                   1           2        3       4          5           6              7              8

if [ ! -e "$2" ]; then
    echo "Destination for package $1: $2 does not exist. Making..."
    mkdir -p $2 2>/dev/null
fi

# backup permission

#   |   Starting version 1.2
#   |   permissions are backed up using
#   |   Java based methods

#if [ "$8" = true ]; then
#    perms="$(dumpsys package $1 | grep android.permission | grep granted=true)"
#    if [ -n "$perms" ]; then
#        echo "$perms" > "$2/$1.perm"
#    else
#        echo "no_permissions_granted" > "$2/$1.perm"
#    fi
#fi

# backup apk
cd $3; cp $4 "$2/$1.apk"
if [ -e "$2/$1.apk" ]; then
    echo "Apk copied"
fi

# backup data
if [ ! "$6" = "NULL" ]; then
    if [ -e "$5/$6" ]; then
        cd "$5"
        $7 tar -vczpf "$2/$1.tar.gz" "$6"
    else
        echo "Data path : $5/$6 does not exist"
    fi
fi