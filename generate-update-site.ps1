# ============================================================
#  generate-update-site.ps1
#  Generates a P2 update site for the ABAP AI Completion plugin
#
#  The output 'update-site/' directory can be:
#    (A) Uploaded to a web server for "Install New Software" URL
#    (B) Zipped and shared as an archive file
# ============================================================

$PROJ = "C:\Users\96000217\Documents\trae_projects\com.sap.abap.ai.completion"
$JAVA_HOME = "C:\Users\96000217\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_23.0.2.v20250131-0604\jre"
$jar = "$JAVA_HOME\bin\jar"

$PLUGIN_JAR = "$PROJ\dist\com.sap.abap.ai.completion_1.0.2.jar"
$SITE_DIR   = "$PROJ\update-site"
$PLUGINS_DIR = "$SITE_DIR\plugins"
$FEATURES_DIR = "$SITE_DIR\features"

$FEATURE_ID = "com.sap.abap.ai.completion.feature"
$FEATURE_VERSION = "1.0.2"
$PLUGIN_ID = "com.sap.abap.ai.completion"
$PLUGIN_VERSION = "1.0.2"

# ---- Pre-checks ----
if (-not (Test-Path $PLUGIN_JAR)) {
    Write-Host "ERROR: Plugin JAR not found at $PLUGIN_JAR" -ForegroundColor Red
    Write-Host "Please run build.ps1 first." -ForegroundColor Yellow
    exit 1
}

# ---- Clean previous site ----
Write-Host "=== Cleaning previous update site ===" -ForegroundColor Cyan
Remove-Item -Recurse -Force $SITE_DIR -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $PLUGINS_DIR | Out-Null
New-Item -ItemType Directory -Force -Path $FEATURES_DIR | Out-Null

# ============================================================
# Step 1: Copy plugin JAR
# ============================================================
Write-Host "=== Copying plugin JAR ===" -ForegroundColor Cyan
Copy-Item $PLUGIN_JAR "$PLUGINS_DIR\" -Force
Write-Host "  -> plugins/$PLUGIN_ID`_$PLUGIN_VERSION.jar"

# ============================================================
# Step 2: Create feature JAR
# ============================================================
Write-Host "=== Creating feature JAR ===" -ForegroundColor Cyan

$TMP = "$PROJ\build_tmp_feature"
Remove-Item -Recurse -Force $TMP -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$TMP\META-INF" | Out-Null

$featureXml = @'
<?xml version="1.0" encoding="UTF-8"?>
<feature
      id="com.sap.abap.ai.completion.feature"
      label="ABAP AI Completion Feature"
      version="1.0.2"
      provider-name="SAP ABAP AI Tools">

   <description>
      AI-powered code completion for ABAP in Eclipse.
   </description>

   <copyright>
      Copyright (c) 2025 SAP ABAP AI Tools. All rights reserved.
   </copyright>

   <license url="">
MIT License

Copyright (c) 2025 SAP ABAP AI Tools

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
   </license>

   <plugin
         id="com.sap.abap.ai.completion"
         download-size="0"
         install-size="0"
         version="1.0.2"
         unpack="false"/>

</feature>
'@

Set-Content -Path "$TMP\feature.xml" -Value $featureXml -Encoding UTF8
Set-Content -Path "$TMP\META-INF\MANIFEST.MF" -Value "Manifest-Version: 1.0`r`n" -Encoding ASCII

Push-Location $TMP
& $jar cf "$FEATURES_DIR\$FEATURE_ID`_$FEATURE_VERSION.jar" feature.xml META-INF/
Pop-Location

Remove-Item -Recurse -Force $TMP -ErrorAction SilentlyContinue
Write-Host "  -> features/$FEATURE_ID`_$FEATURE_VERSION.jar"

# ============================================================
# Step 3: Write site.xml
# ============================================================
Write-Host "=== Creating site.xml ===" -ForegroundColor Cyan

$siteXml = @'
<?xml version="1.0" encoding="UTF-8"?>
<site>
   <feature url="features/com.sap.abap.ai.completion.feature_1.0.2.jar"
            id="com.sap.abap.ai.completion.feature"
            version="1.0.2">
      <category name="ABAP AI Completion"/>
   </feature>
   <category-def name="ABAP AI Completion" label="ABAP AI Completion">
      <description>
         AI-powered code completion for ABAP development in Eclipse.
      </description>
   </category-def>
</site>
'@

Set-Content -Path "$SITE_DIR\site.xml" -Value $siteXml -Encoding UTF8

# ============================================================
# Step 4: Generate content.xml and artifacts.xml
#         (P2 metadata Eclipse can read directly)
# ============================================================
Write-Host "=== Generating P2 metadata ===" -ForegroundColor Cyan

$timeStamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$pluginFile = Get-Item "$PLUGINS_DIR\com.sap.abap.ai.completion_1.0.2.jar"
$featureFile = Get-Item "$FEATURES_DIR\com.sap.abap.ai.completion.feature_1.0.2.jar"
$pluginSize = $pluginFile.Length
$featureSize = $featureFile.Length

$contentXml = @"
<?xml version='1.0' encoding='UTF-8'?>
<?metadataRepository version='1.1.0'?>
<repository name='ABAP AI Completion Update Site'
            type='org.eclipse.equinox.p2.metadata.repository.simpleRepository'
            version='1'>
  <properties size='2'>
    <property name='p2.timestamp' value='$timeStamp'/>
    <property name='p2.compressed' value='true'/>
  </properties>
  <units size='2'>

    <!-- Feature (group) IU -->
    <unit id='com.sap.abap.ai.completion.feature.feature.group'
          version='1.0.2' singleton='false'>
      <update id='com.sap.abap.ai.completion.feature.feature.group'
              range='[0.0.0,1.0.2]' severity='0'/>
      <properties size='4'>
        <property name='org.eclipse.equinox.p2.name'
                  value='ABAP AI Completion Feature'/>
        <property name='org.eclipse.equinox.p2.description'
                  value='AI-powered code completion for ABAP in Eclipse.'/>
        <property name='org.eclipse.equinox.p2.provider'
                  value='SAP ABAP AI Tools'/>
        <property name='org.eclipse.equinox.p2.category'
                  value='ABAP AI Completion'/>
      </properties>
      <provides size='2'>
        <provided namespace='org.eclipse.equinox.p2.iu'
                  name='com.sap.abap.ai.completion.feature.feature.group'
                  version='1.0.2'/>
        <provided namespace='org.eclipse.equinox.p2.iu'
                  name='com.sap.abap.ai.completion.feature.feature.jar'
                  version='1.0.2'/>
      </provides>
      <requires size='1'>
        <required namespace='org.eclipse.equinox.p2.iu'
                  name='com.sap.abap.ai.completion'
                  range='[1.0.2,1.0.2]'/>
      </requires>
      <touchpoint id='org.eclipse.equinox.p2.osgi' version='1.0.2'/>
      <touchpointData size='1'>
        <instructions size='1'>
          <instruction key='manifest'>
            Bundle-SymbolicName: com.sap.abap.ai.completion.feature; singleton:=true
Bundle-Version: 1.0.2
          </instruction>
        </instructions>
      </touchpointData>
    </unit>

    <!-- Plugin (bundle) IU -->
    <unit id='com.sap.abap.ai.completion'
          version='1.0.2' singleton='false'>
      <update id='com.sap.abap.ai.completion'
              range='[0.0.0,1.0.2]' severity='0'/>
      <properties size='3'>
        <property name='org.eclipse.equinox.p2.name'
                  value='ABAP AI Completion'/>
        <property name='org.eclipse.equinox.p2.description'
                  value='AI-powered code completion for ABAP'/>
        <property name='org.eclipse.equinox.p2.provider'
                  value='SAP ABAP AI Tools'/>
      </properties>
      <provides size='1'>
        <provided namespace='org.eclipse.equinox.p2.iu'
                  name='com.sap.abap.ai.completion'
                  version='1.0.2'/>
      </provides>
      <requires size='8'>
        <required namespace='osgi.bundle'
                  name='org.eclipse.ui' range='0.0.0'/>
        <required namespace='osgi.bundle'
                  name='org.eclipse.core.runtime' range='0.0.0'/>
        <required namespace='osgi.bundle'
                  name='org.eclipse.jface' range='0.0.0'/>
        <required namespace='osgi.bundle'
                  name='org.eclipse.jface.text' range='0.0.0'/>
        <required namespace='osgi.bundle'
                  name='org.eclipse.ui.workbench.texteditor' range='0.0.0'/>
        <required namespace='osgi.bundle'
                  name='org.eclipse.core.resources' range='0.0.0'/>
        <required namespace='osgi.bundle'
                  name='org.eclipse.ui.ide' range='0.0.0'/>
        <required namespace='osgi.bundle'
                  name='org.eclipse.swt' range='0.0.0'/>
      </requires>
      <touchpoint id='org.eclipse.equinox.p2.osgi' version='1.0.2'/>
      <touchpointData size='1'>
        <instructions size='1'>
          <instruction key='manifest'>
            Bundle-SymbolicName: com.sap.abap.ai.completion
Bundle-Version: 1.0.2
          </instruction>
        </instructions>
      </touchpointData>
    </unit>

  </units>
</repository>
"@

$artifactsXml = @"
<?xml version='1.0' encoding='UTF-8'?>
<?artifactRepository version='1.1.0'?>
<repository name='ABAP AI Completion Update Site'
            type='org.eclipse.equinox.p2.artifact.repository.simpleRepository'
            version='1'>
  <properties size='2'>
    <property name='p2.timestamp' value='$timeStamp'/>
    <property name='p2.compressed' value='true'/>
  </properties>
  <mappings size='2'>
    <rule filter='(&amp; (classifier=osgi.bundle))'
          output='`${repoUrl}/plugins/'/>
    <rule filter='(&amp; (classifier=org.eclipse.update.feature))'
          output='`${repoUrl}/features/'/>
  </mappings>
  <artifacts size='2'>
    <artifact classifier='osgi.bundle'
              id='com.sap.abap.ai.completion'
              version='1.0.2'>
      <properties size='2'>
        <property name='artifact.size' value='$pluginSize'/>
        <property name='download.size' value='$pluginSize'/>
      </properties>
    </artifact>
    <artifact classifier='org.eclipse.update.feature'
              id='com.sap.abap.ai.completion.feature'
              version='1.0.2'>
      <properties size='2'>
        <property name='artifact.size' value='$featureSize'/>
        <property name='download.size' value='$featureSize'/>
      </properties>
    </artifact>
  </artifacts>
</repository>
"@

Set-Content -Path "$SITE_DIR\content.xml" -Value $contentXml -Encoding UTF8
Set-Content -Path "$SITE_DIR\artifacts.xml" -Value $artifactsXml -Encoding UTF8

# ============================================================
# Step 5: Create content.jar and artifacts.jar (wrapped XML)
#         P2 prefers JAR'd metadata but can also read XML
# ============================================================
Write-Host "=== Packaging P2 metadata into JARs ===" -ForegroundColor Cyan

# Create content.jar
Push-Location $SITE_DIR
& $jar cfM content.jar content.xml
& $jar cfM artifacts.jar artifacts.xml
Pop-Location

Write-Host "  -> content.jar"
Write-Host "  -> artifacts.jar"

# ============================================================
# Step 6: Create ZIP archive
# ============================================================
Write-Host "=== Creating ZIP archive ===" -ForegroundColor Cyan
$zipFile = "$PROJ\dist\ABAP-AI-Completion-UpdateSite-1.0.2.zip"
Remove-Item $zipFile -ErrorAction SilentlyContinue
Compress-Archive -Path "$SITE_DIR\*" -DestinationPath $zipFile -Force

# ============================================================
# Summary
# ============================================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "  P2 UPDATE SITE GENERATED SUCCESSFULLY" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Directory:"
Write-Host "    $SITE_DIR"
Write-Host ""
Write-Host "  Contents:"
Get-ChildItem -Path $SITE_DIR -Recurse | Where-Object { -not $_.PSIsContainer } |
    ForEach-Object { "    $($_.Name)".PadRight(50) + ("$($(($_.Length / 1KB).ToString('0.0'))) KB") }
Write-Host ""
Write-Host "  ZIP Archive:"
Write-Host "    $zipFile"
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  HOW TO USE" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  [A] Install from Web Server (Recommended)"
Write-Host "  -----------------------------------------"
Write-Host "  1. Upload ALL files from '$SITE_DIR'"
Write-Host "     to your web server (e.g., https://your-site.com/eclipse-update/)"
Write-Host "  2. In Eclipse: Help -> Install New Software..."
Write-Host "  3. Click Add... -> set Name and Location URL:"
Write-Host "     https://your-site.com/eclipse-update/"
Write-Host "  4. Check 'ABAP AI Completion Feature' -> Next -> Finish"
Write-Host ""
Write-Host "  [B] Install from ZIP (Offline)"
Write-Host "  ------------------------------"
Write-Host "  1. Share: $zipFile"
Write-Host "  2. In Eclipse: Help -> Install New Software..."
Write-Host "  3. Click Add... -> Archive... -> select the ZIP file"
Write-Host "  4. Check 'ABAP AI Completion Feature' -> Next -> Finish"
Write-Host ""
Write-Host "  [C] Eclipse Marketplace (Requires Marketplace submission)"
Write-Host "  ---------------------------------------------------------"
Write-Host "  See: https://marketplace.eclipse.org/content/abap-ai-completion"
Write-Host ""
Write-Host "============================================" -ForegroundColor Green

