@echo off
title CampusLink Startup

echo =====================================
echo Starting CampusLink...
echo =====================================

echo.
echo [1/3] Starting Docker Containers...
docker compose up -d

echo.
echo [2/3] Starting Backend...
start "CampusLink Backend" cmd /k "cd backend && mvn spring-boot:run"

echo.
echo [3/3] Starting Frontend...
start "CampusLink Frontend" cmd /k "cd frontend_web && npm run dev"

echo.
echo =====================================
echo CampusLink Started
echo =====================================
echo.
echo Backend : http://localhost:8080
echo Frontend: http://localhost:5173
echo Admin   : http://localhost:5173/admin
echo.

pause