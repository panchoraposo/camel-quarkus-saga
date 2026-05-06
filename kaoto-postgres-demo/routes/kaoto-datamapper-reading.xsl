<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

  <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes" indent="yes"/>

  <xsl:template match="/">
    <xsl:variable name="sensorId" select="normalize-space(/reading/sensorId)"/>
    <xsl:variable name="location" select="normalize-space(/reading/location)"/>
    <xsl:variable name="tempC" select="normalize-space(/reading/tempC)"/>
    <xsl:variable name="humidity" select="normalize-space(/reading/humidity)"/>

    <xsl:variable name="severity">
      <xsl:choose>
        <xsl:when test="number($tempC) &gt; 30 or number($humidity) &gt; 70">ALERT</xsl:when>
        <xsl:otherwise>NORMAL</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>

    <xsl:variable name="zone">
      <xsl:choose>
        <xsl:when test="$location = 'lab'">RND</xsl:when>
        <xsl:when test="$location = 'office'">HQ</xsl:when>
        <xsl:when test="$location = 'warehouse'">OPS</xsl:when>
        <xsl:otherwise>UNKNOWN</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>

    <canonicalReading>
      <sensor>
        <id><xsl:value-of select="$sensorId"/></id>
        <location><xsl:value-of select="$location"/></location>
        <zone><xsl:value-of select="$zone"/></zone>
      </sensor>
      <metrics>
        <tempC><xsl:value-of select="$tempC"/></tempC>
        <humidity><xsl:value-of select="$humidity"/></humidity>
      </metrics>
      <severity><xsl:value-of select="normalize-space($severity)"/></severity>
      <unitSystem>metric</unitSystem>
    </canonicalReading>
  </xsl:template>

</xsl:stylesheet>

