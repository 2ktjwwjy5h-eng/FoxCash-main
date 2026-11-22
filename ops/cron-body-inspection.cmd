@echo off
REM cron-body-inspection.cmd — entry point do Agendador de Tarefas do Windows (R5).
REM Chama o wrapper bash com caminho absoluto. O Task Scheduler herda o PATH do
REM usuário (inclui %APPDATA%\npm, onde está a CLI railway).
"C:\Program Files\Git\bin\bash.exe" -lc "bash C:/caminho/do/projeto/ops/cron-body-inspection.sh"
exit %errorlevel%
