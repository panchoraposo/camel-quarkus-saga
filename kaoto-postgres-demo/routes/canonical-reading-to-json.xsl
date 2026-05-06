<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

  <xsl:output method="text" encoding="UTF-8" omit-xml-declaration="yes"/>

  <xsl:template match="/">
    <xsl:variable name="id" select="normalize-space(/canonicalReading/sensor/id)"/>
    <xsl:variable name="location" select="normalize-space(/canonicalReading/sensor/location)"/>
    <xsl:variable name="zone" select="normalize-space(/canonicalReading/sensor/zone)"/>
    <xsl:variable name="tempC" select="normalize-space(/canonicalReading/metrics/tempC)"/>
    <xsl:variable name="humidity" select="normalize-space(/canonicalReading/metrics/humidity)"/>
    <xsl:variable name="severity" select="normalize-space(/canonicalReading/severity)"/>
    <xsl:variable name="unitSystem" select="normalize-space(/canonicalReading/unitSystem)"/>

    <xsl:text>{</xsl:text>
    <xsl:text>"sensor":{"id":"</xsl:text><xsl:value-of select="$id"/><xsl:text>","location":"</xsl:text><xsl:value-of select="$location"/><xsl:text>","zone":"</xsl:text><xsl:value-of select="$zone"/><xsl:text>"},</xsl:text>
    <xsl:text>"metrics":{"tempC":</xsl:text><xsl:value-of select="$tempC"/><xsl:text>,"humidity":</xsl:text><xsl:value-of select="$humidity"/><xsl:text>},</xsl:text>
    <xsl:text>"severity":"</xsl:text><xsl:value-of select="$severity"/><xsl:text>",</xsl:text>
    <xsl:text>"unitSystem":"</xsl:text><xsl:value-of select="$unitSystem"/><xsl:text>"</xsl:text>
    <xsl:text>}</xsl:text>
  </xsl:template>

</xsl:stylesheet>

