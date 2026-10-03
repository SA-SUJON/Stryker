case "$-" in
    *i*) ;;
    *) return 0 2>/dev/null || exit 0 ;;
esac

__s_rail='\[\033[1;38;5;33m\]'
__s_path='\[\033[38;5;75m\]'
__s_dim='\[\033[2m\]'
__s_rst='\[\033[0m\]'

if [ "$(id -u 2>/dev/null)" = "0" ]; then
    __s_brand='\[\033[1;38;5;39m\]'
else
    __s_brand='\[\033[1;38;5;33m\]'
fi
__s_tip="${__s_brand}"'\$'"${__s_rst}"

PS1="${__s_rail}┌──${__s_rst}${__s_dim}(${__s_rst}${__s_brand}⚡Stryker${__s_rst}${__s_dim})${__s_rst}${__s_dim}─[${__s_rst}${__s_path}\\w${__s_rst}${__s_dim}]${__s_rst}\n${__s_rail}└─${__s_rst}${__s_tip} "
PS2="${__s_rail}└─${__s_rst}${__s_dim}>${__s_rst} "
export PS1 PS2
[ -z "$TERM" ] && export TERM=xterm-256color

alias ls='ls --color=auto 2>/dev/null || ls'
alias ll='ls -lh'
alias la='ls -lha'
alias l='ls -CF'
alias ..='cd ..'
alias ...='cd ../..'
alias grep='grep --color=auto'
alias egrep='egrep --color=auto'
alias ports='netstat -tulpn 2>/dev/null || ss -tulpn'

if [ -z "$__STRYKER_GREETED" ]; then
    __STRYKER_GREETED=1
    printf '\033[1;38;5;39m⚡ STRYKER\033[0m \033[2m- pentest shell\033[0m\n'
fi

unset __s_rail __s_path __s_dim __s_rst __s_brand __s_tip
