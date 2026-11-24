# JR NetBeans Pilot (jrnetbeanspilot)
# Copyright 2026 Jose Rodriguez Arroyo
# Developer: Jose Rodriguez Arroyo <jrpcone@gmail.com>
# Website: https://microrepair.net | GitHub: https://github.com/jorodriguezpr
# Project: https://github.com/jorodriguezpr/jrnetbeanspilot
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#
# Build: unit tests + the NetBeans plugin (.nbm) -> dist\jrnetbeanspilot-<version>.nbm
#   powershell -ExecutionPolicy Bypass -File build.ps1 [-NoTests]
param([switch]$NoTests)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$mvnArgs = @('-B', '-q', 'clean', 'package')
if ($NoTests) { $mvnArgs += '-DskipTests' }
Write-Host "== mvn $($mvnArgs -join ' ')"
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { throw "Maven build failed ($LASTEXITCODE)" }
$nbm = Get-ChildItem target\*.nbm | Select-Object -First 1
New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item $nbm.FullName dist\ -Force
Write-Host "== dist\$($nbm.Name) ($([int]($nbm.Length / 1KB)) KB)"
Write-Host "Install: NetBeans > Tools > Plugins > Downloaded > Add Plugins... > dist\$($nbm.Name) > Install"
