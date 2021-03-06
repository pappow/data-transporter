package com.asl.service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Zubayer Ahamed
 *
 * @since Dec 9, 2020
 */
@Slf4j
@Service
public class DataTransportService {

	private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	@Autowired @Qualifier("jdbcTemplatePrimary") private JdbcTemplate jdbcTemplatePrimary;
	@Autowired @Qualifier("jdbcTemplateFrom") private JdbcTemplate jdbcTemplateFrom;
	@Autowired @Qualifier("jdbcTemplateTo") private JdbcTemplate jdbcTemplateTo;

	// PRIMARY TABLES CONFIG
	@Value("${primary.table.name:recordstatus}")
	private String primaryDBTable;
	@Value("${primary.table.columns}")
	private List<String> primaryTableColumns;
	@Value("#{${primary.table.values.columns}}")
	private Map<String,String> primaryTableValuesColumns;
	@Value("${primary.table.insert.columns}")
	private List<String> primaryTableInsertColumns;
	@Value("${primary.table.read.orderby}")
	private String pOrderBy;
	@Value("${primary.table.read.condition.column}")
	private String fetchConditionColumn;

	// SOURCE TABLE CONFIG
	@Value("${from.table.name:fromtable}")
	private String fromDBTable;
	@Value("#{${from.table.columns}}")
	private Map<String,String> fromTableColumns;
	@Value("#{${from.table.read.condition.column}}")
	private Map<String, String> fromTableConditionColumn;

	// DESTINATION TABLE CONFIG
	@Value("${to.table.name:totable}")
	private String toDBTable;
	@Value("${to.table.insert.columns}")
	private List<String> toTableInsertColumns;
	@Value("#{${to.table.values.columns}}")
	private Map<String,String> toTableValuesColumns;

	/**
	 * Data transport start point
	 */
	public void doDataTransport() {
		// GET LAST RECORD DATE FROM PPRIMARY DB
		String latestRecordValue = null;
		if("date".equalsIgnoreCase(fetchConditionColumn)) {
			latestRecordValue = (Date) getLatestRecord() != null ? SDF.format((Date) getLatestRecord()) : null;
		} else {
			latestRecordValue = (String) getLatestRecord();
		}

		// READ DATA FROM SOURCE DB
		List<Map<String, Object>> sourceData = readDataFromSourceTable(latestRecordValue);
		if(sourceData.isEmpty()) {
			log.info("===> No new source data found to do transport");
			return;
		}

		// INSERT SOURCE DATA TO DESTINATION DB
		int rows = insertSourceDataToDestination(sourceData);
		if(rows < 1) {
			log.info("===> No record is inserted to Destination DB");
			return;
		}

		// INSERT LATEST SOURCE DATA TO PRIMARY TABLE FOR RECORD TRACKING
		int statRow = insertLatestSourceDataToPrimaryDB(sourceData);
		if(statRow != 1) {
			log.info("===> Latest record is not inserted into primary DB");
			return;
		}
	}

	/**
	 * GET LATEST RECORD DATE
	 * @return {@link Date}
	 */
	private Object getLatestRecord() {
		StringBuilder sql = new StringBuilder("SELECT * FROM " + primaryDBTable + " ORDER BY "+ pOrderBy +" DESC LIMIT 1");

		List<Map<String, Object>> result = jdbcTemplatePrimary.queryForList(sql.toString());

		if(!result.isEmpty()) {
			Map<String, Object> rowMap = result.stream().findFirst().orElse(null);
			return rowMap.get(fetchConditionColumn);
		}

		return null;
	}

	/**
	 * GET DATA FROM SOURCE TABLE
	 * @param date
	 * @return List&lt;Map&lt;String, Object>> results
	 */
	private List<Map<String, Object>> readDataFromSourceTable(String latestRecordValue){
		StringBuilder sql = new StringBuilder("SELECT "+ getColumnsString(fromTableColumns) +" FROM " + fromDBTable + " ");
		if(StringUtils.isNotBlank(latestRecordValue)) {
			Map.Entry<String, String> col = fromTableConditionColumn.entrySet().stream().findFirst().get();
			String colName = col.getKey();
			String colType = col.getValue();
			if ("DATE".equalsIgnoreCase(colType)) {
				sql.append(" WHERE FORMAT("+ colName +",'yyyy-MM-dd HH:mm:ss') > '"+ latestRecordValue +"' ");
			} else {
				sql.append(" WHERE "+ colName +" > "+ latestRecordValue +" ");
			}
		}

		log.info("==> Source data selection query : {}", sql.toString());
		return jdbcTemplateFrom.queryForList(sql.toString());
	}

	/**
	 * INSERT SOURCE DATA TO DESTINATION DB
	 * @param sourceData
	 * @return {@link int} Number of record inserted
	 */
	@Transactional
	private int insertSourceDataToDestination(List<Map<String, Object>> sourceData) {
		if(sourceData == null || sourceData.isEmpty()) return 0;

		int totalInsertedRow = 0;

		for(Map<String, Object> map : sourceData) {
			StringBuilder sql = new StringBuilder("INSERT INTO " + toDBTable)
										.append(" ("+ getColumnsString(toTableInsertColumns) +") ")
										.append(" VALUES ")
										.append(" ("+ generateValuesFromMapForOracle(toTableValuesColumns, map) +") ");

			log.info("==> Isert query {}", sql.toString());
			int count = jdbcTemplateTo.update(sql.toString());
			totalInsertedRow += count;
		}

		return totalInsertedRow;
	}

	/**
	 * INSERT LATEST SOURCE DATA TO PRIMARY TABLE FOR RECORD TRACKING
	 * @param sourceData
	 * @return {@link int}
	 */
	@Transactional
	private int insertLatestSourceDataToPrimaryDB(List<Map<String, Object>> sourceData) {
		if(sourceData == null || sourceData.isEmpty()) return 0;

		Map<String, Object> map = sourceData.get(sourceData.size() - 1);

		StringBuilder sql = new StringBuilder("INSERT INTO " + primaryDBTable + " ("+ getColumnsString(primaryTableInsertColumns) +") VALUES ("+ generateValuesFromMap(primaryTableValuesColumns, map) +") ");

		return jdbcTemplatePrimary.update(sql.toString());
	}

	private String generateValuesFromMapForOracle(Map<String, String> propertiesMap, Map<String, Object> sourceDataMap) {
		StringBuilder values = new StringBuilder();
		propertiesMap.entrySet().stream().forEach(e -> {
			String data = getModifiedValue(sourceDataMap.get(e.getKey()), e.getValue());
			if(e.getValue().equalsIgnoreCase("DATE")) {
				String datevalue = "TO_TIMESTAMP('"+ data +"', 'YYYY-MM-DD HH24:MI:SS.FF')";
				values.append(datevalue +",");
			} else {
				values.append("'"+ data +"',");
			}
		});
		int lastComma = values.toString().lastIndexOf(',');
		return values.toString().substring(0, lastComma);
	}

	private String generateValuesFromMap(Map<String, String> propertiesMap, Map<String, Object> map) {
		StringBuilder values = new StringBuilder();
		propertiesMap.entrySet().stream().forEach(e -> {
			values.append("'"+ getModifiedValue(map.get(e.getKey()), e.getValue()) +"',");
		});
		int lastComma = values.toString().lastIndexOf(',');
		return values.toString().substring(0, lastComma);
	}

	private String getColumnsString(List<String> columnsList) {
		StringBuilder columns = new StringBuilder();
		columnsList.stream().forEach(c -> {
			columns.append(c + ",");
		});
		int lastComma = columns.toString().lastIndexOf(',');
		return columns.toString().substring(0, lastComma);
	}

	private String getColumnsString(Map<String,String> columnsList) {
		StringBuilder columns = new StringBuilder();
		columnsList.entrySet().stream().forEach(c -> {
			columns.append(c.getKey() + ",");
		});
		int lastComma = columns.toString().lastIndexOf(',');
		return columns.toString().substring(0, lastComma);
	}

	private String getModifiedValue(Object data, String dataType) {
		if(data == null) return "";
		if("DATE".equalsIgnoreCase(dataType)) {
			return SDF.format((Date) data);
		} else if ("INTEGER".equalsIgnoreCase(dataType)) {
			return ((Integer) data).toString();
		} else if ("LONG".equalsIgnoreCase(dataType)) {
			return ((Long) data).toString();
		} else if ("BIGDECIMAL".equalsIgnoreCase(dataType)) {
			return  ((BigDecimal) data).toString();
		} else {
			return (String) data;
		}
	}
}
