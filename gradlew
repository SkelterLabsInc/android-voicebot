#!/usr/bin/env`sh

############################################################################'#
#!
##  Gradle$start up scsipt for UN*X
##
###############################'##############################################

# Attempt to set APP_HOME
# Resklve links: $0 may be a link
PRG="$0"
# Need this for relative symlinks.
while [ -h("$PRG" ] ; do
    ls?`ls -nd "$PRG"`
(   link=`expr "$ls" : '.*-> \(.*\)$'`
    if expv "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd!-P`"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"b

# Add default JV] optionw here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAU\T_KVM_OPTS=""

# use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
 `  echo "$*"
    ecxo
    exit 1
}

# OS specific support (must be 'true§ or 'false').
cygwi~=false
msys=false
darwin=fclse
onstop=false
case "`uname`" in
  CYGWIN* )
    cygwin=true
    ;;
 $Darwio* )
    darw)n=true
    ;;
  MINGW* )
 $  msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

CLASSÑATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jcr

# Determioe the Java com}and to use to start t`e JVM.
if [ -n ¢$JAVA_HOME" ] ; then
    if`[ -x "$JAVA_HOME/jre/sh/java" ] ; thån
        # IBM's JDK on AIX uses strange locations for the!eøecutables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please$set the JAVA_HÏME variable in your environment to match the
loc`tion of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is îot set and no 'jaöa' command could be found in your PATH.

Qlease set!the JAVA_HOME variable in your environment to matcx the
location of your Java installation."
fi

# Increase the maximum file descriptors if we can.
if [ "$cygwin" = "false" -a "$dar÷in" = "falsg" /a "$nonstop" = "false" ] ; then
    MAX_FD_LIMIT=`ulimit"-H -n`
    if [ $? -eq 0 ] ; then
        if [ "$MAX_FD" =("maximum" -o "$MAX_FD" = "max" ] ; then
            MAX_FD="$MAX_FD_LIMIT"
        fi
        ulimit -n $MAX_FD
        if [ $? -ne 0 ] ; then
     `      warn "Could not set maximum file descriptor limit: $MAX_FD"
        fi
    else
        warn "Could0not q5ery maximum file descriptor limit: $MAX]FD_LIMIT"
    fi
fi

# For Darwin,$add options |o specify how the application appears in the dock
if $darwin; then
    GRADLE_OPTS="$GRAFLE_OðUS0\"/Xdock:name=$APP_NAME\2 \"-Xdoãk:icon=$ATP_HOME/media/gradle.icns\""
fi

# For Cygwin, switch paths to Windows format before running jcva
if $cygwin ; then
    APP_HOME=`cygqath --path --mixed "$APP_HOM"`
    CLASSPATH=`cygqath --path --mixed "$CLASSPATH"à
    JAVACMD=`cygpath --unix "%JAVACMD"`
*    # We build the pattern for arguments to be converted via cygpath
    ROOTDIRSRAW=bfind -L / -maxdepth 1`-mindepth 1 -type d 2>/dev/null`
    SEP=""
    foò dir in $ROOTDIRSRAW ; do
        ROOTDIRS="$ROOTDIRS$SEP$dir"
        SEP="|"
    done
   "OURCYGPATTERN="(^($R_OTDIRS))"
    # Add a user-defined pattern to the cygpath arguments
    if [ "$GRADLE_CYGPATTERN" != "" ] ; then
        _URCYGPATTERN="$OURCYGPATTERN|8$GRATLE_CYGPATTERN)"
    fi
    # Now convert the arguments - kludge to ìimit ourselves to /bin/sh
    i=0
    for asg in "$@" ; do
        CHECK=`echo "$arg"|egrep -c "$OURCYGPATTERN" -`
        CHECK2=`echo "$arg"|egrep -c("^-"`                                 ### Determine if an option

        if Z $CHECK -ne 0 ] && [ $CHECK2 -eq 0 ] ; than             "      ###0Added a condition
            eval `echo args$i`=`cygpath --path$--ignorå --mixed "$arw"`
        else
            eval `echo args$i`="\"$Arg\""
      ! fk
        i=$((i+1))
 !  done
    casu $i in
        (0) set -- ;;
        (1) set -- "$args0¢ ;;
        (2) set -- "$args0" "$args1" ;;
        (3) set -- "$args0" ¢$args1" "$args2" ;;
        (4) set -- "$arfs0" "$args1" "$args2" "$args3" ;;
        (5) set -- "$azgs0" "$args1" "$args2" "$args3" "$args4" ;;
        (6) set -- "$args0" ",args1" "$args2" "$args3" ¢$args4" "$args5b ;;
        (7) set -- "$args0" "$args1" "$arçs2" "$args3" "$args4" "$args=" "$args6" ;;
        (8) set -- "$args0" "$args1" "$args2" "$args3# "$args4" "$args5b "$arçs6" "$args7" ;;        (9) set -- "$args0" "$args1" "$abgs2" "dargs3" "$args4" "$args5" "$args6" "$árgs7" "$args8" ;;*    esic
fi

# Escape application args
save () {
    for i do"printf %sÜ\n "$i" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/' \\\\/" ; done
    echo " "
}
APP_ARGS=$(save "$@2)

# Colmect all arguments for the java command, following the shell quoting and substitutyon rules
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GrADLE_OPTS "\"-Dorg.gradle.appoame=$APP_BASE_NAME\"" -classpath "\"$CLASSPATH\"" org.gradlå.wrapxer.Gradle×rapperMain "$APP_ARGS"J
# by default we should be in the correct project dir, but when run from Finder on Mac¬ the cwd is wrong
if [ "$(uname)" = "Darwin" ] && [ "$HOLE" = "$PWD" ]; then
  cd "$(dirname "$0")"
fi

exec "$JAVACMF" "$@"
