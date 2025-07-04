/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
grammar PhoenixSQL;

tokens
{
    SELECT='select';
    FROM='from';
    WHERE='where';
    NOT='not';
    AND='and';
    OR='or';
    NULL='null';
    TRUE='true';
    FALSE='false';
    LIKE='like';
    ILIKE='ilike';
    AS='as';
    OUTER='outer';
    ON='on';
    OFF='off';
    IN='in';
    GROUP='group';
    HAVING='having';
    ORDER='order';
    BY='by';
    ASC='asc';
    DESC='desc';
    NULLS='nulls';
    LIMIT='limit';
    FIRST='first';
    LAST='last';
    CASE='case';
    WHEN='when';
    THEN='then';
    ELSE='else';
    END='end';
    EXISTS='exists';
    IS='is';
    FIRST='first';    
    DISTINCT='distinct';
    JOIN='join';
    INNER='inner';
    LEFT='left';
    RIGHT='right';
    FULL='full';
    BETWEEN='between';
    UPSERT='upsert';
    INTO='into';
    VALUES='values';
    DELETE='delete';
    CREATE='create';
    DROP='drop';
    PRIMARY='primary';
    KEY='key';
    ALTER='alter';
    COLUMN='column';
    SESSION='session';
    TABLE='table';
    SCHEMA='schema';
    SCHEMAS='schemas';
    ADD='add';
    SPLIT='split';
    EXPLAIN='explain';
    VIEW='view';
    IF='if';
    CONSTRAINT='constraint';
    TABLES='tables';
    CDC='cdc';
    PRE='pre';
    POST='post';
    CHANGE='change';
    LATEST='latest';
    ALL='all';
    INDEX='index';
    INCLUDE='include';
    WITHIN='within';
    SET='set';
    CAST='cast';
    ACTIVE='active';
    USABLE='usable';
    UNUSABLE='unusable';
    DISABLE='disable';
    REBUILD='rebuild';
    ARRAY='array';
    SEQUENCE='sequence';
    START='start';
    WITH='with';
    INCREMENT='increment';
    NEXT='next';
    CURRENT='current';
    VALUE='value';
    FOR='for';
    CACHE='cache';
    LOCAL='local';
    ANY='any';
    SOME='some';
    MINVALUE='minvalue';
    MAXVALUE='maxvalue';
    CYCLE='cycle';
    CASCADE='cascade';
    UPDATE='update';
    UPDATE_ONLY='update_only';
    STATISTICS='statistics';
    COLUMNS='columns';
    TRACE='trace';
    ASYNC='async';
    SAMPLING='sampling';
    TABLESAMPLE='tablesample';
    UNION='union';
    FUNCTION='function';
    AS='as';
    TO='to';
    TEMPORARY='temporary';
    RETURNS='returns';
    USING='using';
    JAR='jar';
    DEFAULTVALUE='defaultvalue';
    CONSTANT = 'constant';
    REPLACE = 'replace';
    LIST = 'list';
    JARS='jars';
    ROW_TIMESTAMP='row_timestamp';
    ENCODED_QUALIFIER = 'encoded_qualifier';
    COLUMN_QUALIFIER_COUNTER = 'column_qualifier_counter';
    USE='use';
    OFFSET ='offset';
    FETCH = 'fetch';
    DECLARE = 'declare';
    CURSOR = 'cursor';
    OPEN = 'open';
    CLOSE = 'close';
    ROW = 'row';
    ROWS = 'rows';
    ONLY = 'only';
    EXECUTE = 'execute';
    UPGRADE = 'upgrade';
    DEFAULT = 'default';
    DUPLICATE = 'duplicate';
    IGNORE = 'ignore';
    IMMUTABLE = 'immutable';
    GRANT = 'grant';
    REVOKE = 'revoke';
    SHOW = 'show';
    UNCOVERED = 'uncovered';
    REGIONS = 'regions';
    NOVERIFY = 'noverify';
}


@parser::header {
/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.parse;

///CLOVER:OFF
import org.apache.phoenix.thirdparty.com.google.common.collect.ImmutableMap;
import org.apache.phoenix.thirdparty.com.google.common.collect.ArrayListMultimap;
import org.apache.phoenix.thirdparty.com.google.common.collect.ListMultimap;
import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.util.Pair;
import java.lang.Boolean;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.sql.SQLException;
import org.apache.phoenix.expression.function.CountAggregateFunction;
import org.apache.phoenix.expression.LiteralExpression;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.parse.PFunction;
import org.apache.phoenix.parse.PFunction.FunctionArgument;
import org.apache.phoenix.parse.UDFParseNode;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.IllegalDataException;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.PTable.IndexType;
import org.apache.phoenix.schema.PTable.CDCChangeScope;
import org.apache.phoenix.schema.stats.StatisticsCollectionScope;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PDate;
import org.apache.phoenix.schema.types.PTime;
import org.apache.phoenix.schema.types.PTimestamp;
import org.apache.phoenix.schema.types.PUnsignedDate;
import org.apache.phoenix.schema.types.PUnsignedTime;
import org.apache.phoenix.schema.types.PUnsignedTimestamp;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.parse.LikeParseNode.LikeType;
import org.apache.phoenix.trace.util.Tracing;
import org.apache.phoenix.parse.AddJarsStatement;
import org.apache.phoenix.parse.ExplainType;
}

@lexer::header {
/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.parse;
///CLOVER:OFF
}

// --------------------------------------
// The Parser

@parser::members
{
    
    /**
     * used to turn '?' binds into : binds.
     */
    private int anonBindNum;
    private ParseNodeFactory factory;
    private ParseContext.Stack contextStack = new ParseContext.Stack();
    private Map<String, UDFParseNode> udfParseNodes = new HashMap<String, UDFParseNode>(1);

    public void setParseNodeFactory(ParseNodeFactory factory) {
        this.factory = factory;
    }
    
    public boolean isCountFunction(String field) {
        return CountAggregateFunction.NORMALIZED_NAME.equals(SchemaUtil.normalizeIdentifier(field));
    }
     
    public int line(Token t) {
        return t.getLine();
    }

    public int column(Token t) {
        return t.getCharPositionInLine() + 1;
    }
    
    private void throwRecognitionException(Token t) throws RecognitionException {
        RecognitionException e = new RecognitionException();
        e.token = t;
        e.line = t.getLine();
        e.charPositionInLine = t.getCharPositionInLine();
        e.input = input;
        throw e;
    }
    
    public int getBindCount() {
        return anonBindNum;
    }
    
    public void resetBindCount() {
        anonBindNum = 0;
    }

    public String nextBind() {
        return Integer.toString(++anonBindNum);
    }
    
    public void updateBind(String namedBind){
         int nBind = Integer.parseInt(namedBind);
         if (nBind > anonBindNum) {
             anonBindNum = nBind;
         }
    }

    protected Object recoverFromMismatchedToken(IntStream input, int ttype, BitSet follow)
        throws RecognitionException {
        RecognitionException e = null;
        // if next token is what we are looking for then "delete" this token
        if (mismatchIsUnwantedToken(input, ttype)) {
            e = new UnwantedTokenException(ttype, input);
        } else if (mismatchIsMissingToken(input, follow)) {
            Object inserted = getMissingSymbol(input, e, ttype, follow);
            e = new MissingTokenException(ttype, input, inserted);
        } else {
            e = new MismatchedTokenException(ttype, input);
        }
        throw e;
    }

    public Object recoverFromMismatchedSet(IntStream input, RecognitionException e, BitSet follow)
        throws RecognitionException
    {
        throw e;
    }
    
    @Override
    public String getErrorMessage(RecognitionException e, String[] tokenNames) {
        if (e instanceof MismatchedTokenException) {
            MismatchedTokenException mte = (MismatchedTokenException)e;
            String txt = mte.token.getText();
            String p = mte.token.getType() == -1 ? "EOF" : PARAPHRASE[mte.token.getType()];
            String expecting = (mte.expecting < PARAPHRASE.length && mte.expecting >= 0) ? PARAPHRASE[mte.expecting] : null;
            if (expecting == null) {
                return "unexpected token (" + line(mte.token) + "," + column(mte.token) + "): " + (txt != null ? txt : p);
            } else {
                return "expecting " + expecting +
                    ", found '" + (txt != null ? txt : p) + "'";
            }
        } else if (e instanceof NoViableAltException) {
            //NoViableAltException nvae = (NoViableAltException)e;
            return "unexpected token: (" + line(e.token) + "," + column(e.token) + ")" + getTokenErrorDisplay(e.token);
        }
        return super.getErrorMessage(e, tokenNames);
     }

    public String getTokenErrorDisplay(int t) {
        String ret = PARAPHRASE[t];
        if (ret == null) ret = "<UNKNOWN>";
        return ret;
    }


    private String[] PARAPHRASE = new String[getTokenNames().length];
    {
        PARAPHRASE[NAME] = "a field or entity name";
        PARAPHRASE[NUMBER] = "a number";
        PARAPHRASE[EQ] = "an equals sign";
        PARAPHRASE[LT] = "a left angle bracket";
        PARAPHRASE[GT] = "a right angle bracket";
        PARAPHRASE[COMMA] = "a comma";
        PARAPHRASE[LPAREN] = "a left parentheses";
        PARAPHRASE[RPAREN] = "a right parentheses";
        PARAPHRASE[SEMICOLON] = "a semi-colon";
        PARAPHRASE[COLON] = "a colon";
        PARAPHRASE[LSQUARE] = "left square bracket";
        PARAPHRASE[RSQUARE] = "right square bracket";
        PARAPHRASE[LCURLY] = "left curly bracket";
        PARAPHRASE[RCURLY] = "right curly bracket";
        PARAPHRASE[AT] = "at";
        PARAPHRASE[MINUS] = "a subtraction";
        PARAPHRASE[TILDE] = "a tilde";
        PARAPHRASE[PLUS] = "an addition";
        PARAPHRASE[ASTERISK] = "an asterisk";
        PARAPHRASE[DIVIDE] = "a division";
        PARAPHRASE[FIELDCHAR] = "a field character";
        PARAPHRASE[LETTER] = "an ansi letter";
        PARAPHRASE[POSINTEGER] = "a positive integer";
        PARAPHRASE[DIGIT] = "a number from 0 to 9";
    }
}

@rulecatch {
    catch (RecognitionException re) {
        throw re;
    }
}

@lexer::members {

}

// Used to incrementally parse a series of semicolon-terminated SQL statement
// Note than unlike the rule below an EOF is not expected at the end.
nextStatement returns [BindableStatement ret]
    :  s=oneStatement {
    		try {
    			$ret = s;
    		} finally {
    			udfParseNodes.clear();
    		}
    	} SEMICOLON
    |  EOF
    ;

// Parses a single SQL statement (expects an EOF after the select statement).
statement returns [BindableStatement ret]
    :   s=oneStatement {
        		try {
    			$ret = s;
    		} finally {
    			udfParseNodes.clear();
    		}
    	} EOF
    ;

// Parses a select statement which must be the only statement (expects an EOF after the statement).
query returns [SelectStatement ret]
    :   s=select_node EOF {$ret=s;}
    ;

// Parses a single SQL statement (expects an EOF after the select statement).
oneStatement returns [BindableStatement ret]
@init{ contextStack.push(new ParseContext()); }
    :  (s=select_node
    |	s=upsert_node
    |   s=delete_node
    |   s=create_table_node
    |   s=create_schema_node
    |   s=create_view_node
    |   s=create_index_node
    |   s=create_cdc_node
    |   s=cursor_open_node
    |   s=cursor_close_node
    |   s=cursor_fetch_node
    |   s=declare_cursor_node
    |   s=drop_table_node
    |   s=drop_index_node
    |   s=drop_cdc_node
    |   s=alter_index_node
    |   s=alter_table_node
    |   s=show_node
    |   s=show_create_table_node
    |   s=trace_node
    |   s=create_function_node
    |   s=drop_function_node
    |   s=add_jars_node
    |   s=list_jars_node
    |   s=delete_jar_node
    |   s=alter_session_node
    |	s=create_sequence_node
    |   s=grant_permission_node
    |   s=revoke_permission_node
    |	s=drop_sequence_node
    |	s=drop_schema_node
    |	s=use_schema_node
    |   s=update_statistics_node
    |   s=execute_upgrade_node
    |   s=explain_node) { $ret = s; }
    ;
finally{ contextStack.pop(); }
    
explain_node returns [BindableStatement ret]
    :   EXPLAIN (w=WITH)? (r=REGIONS)? q=oneStatement
     {
        if ((w==null && r!=null) || (w!=null && r==null)) {
            throw new RuntimeException("Valid usage: EXPLAIN {query} OR EXPLAIN WITH REGIONS {query}");
        }
        ret = (w==null && r==null) ? factory.explain(q, ExplainType.DEFAULT)
         : factory.explain(q, ExplainType.WITH_REGIONS);
     }
    ;

// Parse a create table statement.
create_table_node returns [CreateTableStatement ret]
    :   CREATE (im=IMMUTABLE)? TABLE (IF NOT ex=EXISTS)? t=from_table_name 
        (LPAREN c=column_defs (pk=pk_constraint)? RPAREN)
        (noverify=NOVERIFY)?
        (p=fam_properties)?
        (SPLIT ON s=value_expression_list)?
        (COLUMN_QUALIFIER_COUNTER LPAREN cqc=initializiation_list RPAREN)?
        {ret = factory.createTable(t, p, c, pk, s, PTableType.TABLE, ex!=null, null, null, getBindCount(), im!=null ? true : null, cqc, noverify!=null); }
    ;
   
// Parse a create schema statement.
create_schema_node returns [CreateSchemaStatement ret]
    :   CREATE SCHEMA (IF NOT ex=EXISTS)? s=identifier
        {ret = factory.createSchema(s, ex!=null); }
    ;

// Parse a grant permission statement
grant_permission_node returns [ChangePermsStatement ret]
    :   GRANT p=literal (ON ((TABLE)? table=table_name | s=SCHEMA schema=identifier))? TO (g=GROUP)? ug=literal
        {
            String permsString = SchemaUtil.normalizeLiteral(p);
            if (permsString != null && permsString.length() > 5) {
                throw new RuntimeException("Permissions String length should be less than 5 characters");
            }
            $ret = factory.changePermsStatement(permsString, s!=null, table, schema, g!=null, ug, Boolean.TRUE);
        }
    ;

// Parse a revoke permission statement
revoke_permission_node returns [ChangePermsStatement ret]
    :   REVOKE (p=literal)? (ON ((TABLE)? table=table_name | s=SCHEMA schema=identifier))? FROM (g=GROUP)? ug=literal
        {
            String permsString = SchemaUtil.normalizeLiteral(p);
            if (permsString != null && permsString.length() > 5) {
                throw new RuntimeException("Permissions String length should be less than 5 characters");
            }
            $ret = factory.changePermsStatement(permsString, s!=null, table, schema, g!=null, ug, Boolean.FALSE);
        }
    ;

// Parse a show statement. SHOW TABLES, SHOW SCHEMAS ...
show_node returns [ShowStatement ret]
    :   SHOW TABLES (IN schema=identifier)? (LIKE pattern=string_literal)? { $ret = factory.showTablesStatement(schema, pattern); }
    |   SHOW SCHEMAS (LIKE pattern=string_literal)? { $ret = factory.showSchemasStatement(pattern); }
    ;

// Parse a describe statement. SHOW CREATE TABLE tablename/viewname/indexname ...
show_create_table_node returns [ShowCreateTable ret]
    :   SHOW CREATE TABLE tablename=from_table_name { $ret = factory.showCreateTable(tablename); }
    ;

// Parse a create view statement.
create_view_node returns [CreateTableStatement ret]
    :   CREATE VIEW (IF NOT ex=EXISTS)? t=from_table_name 
        (LPAREN c=column_defs (pk=pk_constraint)? RPAREN)?
        ( AS SELECT ASTERISK
          FROM bt=from_table_name
          (WHERE w=expression)? )?
        (p=fam_properties)?
        { ret = factory.createTable(t, p, c, pk, null, PTableType.VIEW, ex!=null, bt==null ? t : bt, w, getBindCount(), null); }
    ;

// Parse a create index statement.
create_index_node returns [CreateIndexStatement ret]
    :   CREATE u=UNCOVERED? l=LOCAL? INDEX (IF NOT ex=EXISTS)? i=index_name ON t=from_table_name
        (LPAREN ik=ik_constraint RPAREN)
        (in=INCLUDE (LPAREN icrefs=column_names RPAREN))?
        (WHERE where=expression)?
        (async=ASYNC)?
        (p=fam_properties)?
        (SPLIT ON v=value_expression_list)?
        {
            if (u !=null && in != null) {
                throw new RuntimeException("UNCOVERED indexes cannot have the INCLUDE clause");
            }
            if (l !=null && u != null) {
                throw new RuntimeException("UNCOVERED cannot be used with LOCAL");
            }
            if (l !=null && where != null) {
                throw new RuntimeException("Partial local indexes are not supported");
            }
            ret = factory.createIndex(i, factory.namedTable(null,t), ik, icrefs, v, p, ex!=null,
                    l==null ? (u==null ? IndexType.getDefault() : IndexType.UNCOVERED_GLOBAL) :
                    IndexType.LOCAL, async != null, getBindCount(), new HashMap<String,
                    UDFParseNode>(udfParseNodes), where);
        }
    ;

create_cdc_node returns [CreateCDCStatement ret]
    :   CREATE CDC (IF NOT ex=EXISTS)? o=cdc_name ON t=from_table_name
        (INCLUDE LPAREN v=cdc_change_scopes RPAREN)?
        (p=fam_properties)?
        {
            ret = factory.createCDC(o, t, v, p, ex != null, getBindCount());
        }
    ;

cdc_name returns [NamedNode ret]
    :   name=identifier {$ret = factory.cdcName(name); }
    ;

cdc_change_scopes returns [Set<CDCChangeScope> ret]
@init { ret = new HashSet<>(); }
    :   v=cdc_change_scope { $ret.add(v); } ( COMMA v=cdc_change_scope { $ret.add(v); } )*
    ;

cdc_change_scope returns [CDCChangeScope ret]
    :   v=(PRE | POST | CHANGE)
        {
            ret = CDCChangeScope.valueOf(v.getText().toUpperCase());
        }
    ;

// Parse a create sequence statement.
create_sequence_node returns [CreateSequenceStatement ret]
    :   CREATE SEQUENCE  (IF NOT ex=EXISTS)? t=from_table_name
        (START WITH? s=value_expression)?
        (INCREMENT BY? i=value_expression)?
        (MINVALUE min=value_expression)?
        (MAXVALUE max=value_expression)?
        (cyc=CYCLE)? 
        (CACHE c=int_literal_or_bind)?
    { $ret = factory.createSequence(t, s, i, c, min, max, cyc!=null, ex!=null, getBindCount()); }
    ;

int_literal_or_bind returns [ParseNode ret]
    : n=int_or_long_literal { $ret = n; }
    | b=bind_expression { $ret = b; }
    ;

// Returns the normalized string literal
string_literal returns [String ret]
    :   s=STRING_LITERAL { ret = SchemaUtil.normalizeLiteral(factory.literal(s.getText())); }
    ;

// Parse a drop sequence statement.
drop_sequence_node returns [DropSequenceStatement ret]
    :   DROP SEQUENCE  (IF ex=EXISTS)? t=from_table_name
    { $ret = factory.dropSequence(t, ex!=null, getBindCount()); }
    ;

pk_constraint returns [PrimaryKeyConstraint ret]
    :   COMMA? CONSTRAINT n=identifier PRIMARY KEY LPAREN cols=col_name_with_sort_order_rowtimestamp_list RPAREN { $ret = factory.primaryKey(n,cols); }
    ;

col_name_with_sort_order_rowtimestamp_list returns [List<ColumnDefInPkConstraint> ret]
@init{ret = new ArrayList<ColumnDefInPkConstraint>(); }
    :   p=col_name_with_sort_order_rowtimestamp {$ret.add(p);}  (COMMA p = col_name_with_sort_order_rowtimestamp {$ret.add(p);} )*
;
 
col_name_with_sort_order_rowtimestamp returns [ColumnDefInPkConstraint ret]
    :   f=identifier (order=ASC|order=DESC)? (rr=ROW_TIMESTAMP)?
        { $ret = factory.columnDefInPkConstraint(factory.columnName(f), order == null ? SortOrder.getDefault() : SortOrder.fromDDLValue(order.getText()), rr != null); }
;

ik_constraint returns [IndexKeyConstraint ret]
    :   x = expression_with_sort_order_list {$ret = factory.indexKey(x); }
;

expression_with_sort_order_list returns [List<Pair<ParseNode, SortOrder>> ret]
@init{ret = new ArrayList<Pair<ParseNode, SortOrder>>(); }
    :   p=expression_with_sort_order {$ret.add(p);}  (COMMA p = expression_with_sort_order {$ret.add(p);} )*
;

expression_with_sort_order returns [Pair<ParseNode, SortOrder> ret]
    :   (x=expression) (order=ASC|order=DESC)? {$ret = Pair.newPair(x, order == null ? SortOrder.getDefault() : SortOrder.fromDDLValue(order.getText()));}
;

fam_properties returns [ListMultimap<String,Pair<String,Object>> ret]
@init{ret = ArrayListMultimap.<String,Pair<String,Object>>create(); }
    :  p=fam_prop_name EQ v=prop_value {$ret.put(p.getFamilyName(),new Pair<String,Object>(p.getPropertyName(),v));}  (COMMA p=fam_prop_name EQ v=prop_value {$ret.put(p.getFamilyName(),new Pair<String,Object>(p.getPropertyName(),v));} )*
    ;

fam_prop_name returns [PropertyName ret]
    :   propName=identifier {$ret = factory.propertyName(propName); }
    |   familyName=identifier DOT propName=identifier {$ret = factory.propertyName(familyName, propName); }
    ;
    
prop_value returns [Object ret]
    :   v=identifier { $ret = v; }
    |   l=literal { $ret = l.getValue(); }
    ;
    
column_name returns [ColumnName ret]
    :   field=identifier {$ret = factory.columnName(field); }
    |   family=identifier DOT field=identifier {$ret = factory.columnName(family, field); }
    ;

column_names returns [List<ColumnName> ret]
@init{ret = new ArrayList<ColumnName>(); }
    :  v = column_name {$ret.add(v);}  (COMMA v = column_name {$ret.add(v);} )*
;

	
// Parse a drop table statement.
drop_table_node returns [DropTableStatement ret]
    :   DROP (v=VIEW | TABLE) (IF ex=EXISTS)? t=from_table_name (c=CASCADE)?
        {ret = factory.dropTable(t, v==null ? (QueryConstants.SYSTEM_SCHEMA_NAME.equals(t.getSchemaName()) ? PTableType.SYSTEM : PTableType.TABLE) : PTableType.VIEW, ex!=null, c!=null); }
    ;

drop_schema_node returns [DropSchemaStatement ret]
    :   DROP SCHEMA (IF ex=EXISTS)? s=identifier (c=CASCADE)?
        {ret = factory.dropSchema(s, ex!=null, c!=null); }
    ;


// Parse a drop index statement
drop_index_node returns [DropIndexStatement ret]
    : DROP INDEX (IF ex=EXISTS)? i=index_name ON t=from_table_name
      {ret = factory.dropIndex(i, t, ex!=null); }
    ;

// Parse a drop CDC statement
drop_cdc_node returns [DropCDCStatement ret]
   : DROP CDC (IF ex=EXISTS)? o=cdc_name ON t=from_table_name
     {ret = factory.dropCDC(o, t, ex!=null); }
   ;

// Parse a alter index statement
alter_index_node returns [AlterIndexStatement ret]
    : ALTER INDEX (IF ex=EXISTS)? i=index_name ON t=from_table_name
      ((s=(USABLE | UNUSABLE | REBUILD (isRebuildAll=ALL)? | DISABLE | ACTIVE)) (async=ASYNC)? ((SET?)p=fam_properties)?)
      {ret = factory.alterIndex(factory.namedTable(null, TableName.create(t.getSchemaName(), i.getName())), t.getTableName(), ex!=null, PIndexState.valueOf(SchemaUtil.normalizeIdentifier(s.getText())), isRebuildAll!=null, async!=null, p); }
    ;

// Parse a trace statement.
trace_node returns [TraceStatement ret]
    :   TRACE ((flag = ON  ( WITH SAMPLING s = sampling_rate)?) | flag = OFF)
       {ret = factory.trace(Tracing.isTraceOn(flag.getText()), s == null ? Tracing.isTraceOn(flag.getText()) ? 1.0 : 0.0 : (((BigDecimal)s.getValue())).doubleValue());}
    ;

// Parse a create function statement.
create_function_node returns [CreateFunctionStatement ret]
    :   CREATE (OR replace=REPLACE)? (temp=TEMPORARY)? FUNCTION function=identifier 
       (LPAREN args=zero_or_more_data_types RPAREN)
       RETURNS r=identifier AS (className= jar_path)
       (USING JAR (jarPath = jar_path))?
        {
            $ret = factory.createFunction(new PFunction(SchemaUtil.normalizeIdentifier(function), args,r,(String)className.getValue(), jarPath == null ? null : (String)jarPath.getValue()), temp!=null, replace!=null);
        } 
    ;

jar_path returns [LiteralParseNode ret]
    : l=literal { $ret = l; }
    ;

drop_function_node returns [DropFunctionStatement ret]
    : DROP FUNCTION (IF ex=EXISTS)? function=identifier {$ret = factory.dropFunction(SchemaUtil.normalizeIdentifier(function), ex!=null);}
    ;

add_jars_node returns [AddJarsStatement ret]
    : ADD JARS jarPaths = one_or_more_jarpaths { $ret = factory.addJars(jarPaths);}
    ;

list_jars_node returns [ListJarsStatement ret]
    : LIST JARS { $ret = factory.listJars();}
    ;

delete_jar_node returns [DeleteJarStatement ret]
    : DELETE JAR jarPath = jar_path { $ret = factory.deleteJar(jarPath);}
    ;

// Parse an alter session statement.
alter_session_node returns [AlterSessionStatement ret]
    :   ALTER SESSION (SET p=properties)
       {ret = factory.alterSession(p);}
    ;

// Parse an alter table statement.
alter_table_node returns [AlterTableStatement ret]
    :   ALTER (TABLE | v=VIEW) t=from_table_name
        ( (DROP COLUMN (IF ex=EXISTS)? c=column_names) | (ADD (IF NOT ex=EXISTS)? (d=column_defs) (p=fam_properties)?) (cas=CASCADE INDEX (list=indexes | all=ALL))? | (SET (p=fam_properties)) )
        { PTableType tt = v==null ? (QueryConstants.SYSTEM_SCHEMA_NAME.equals(t.getSchemaName()) ? PTableType.SYSTEM : PTableType.TABLE) : PTableType.VIEW; ret = ( c == null ? factory.addColumn(factory.namedTable(null,t), tt, d, ex!=null, p, cas!=null, (all == null ? list : null)) : factory.dropColumn(factory.namedTable(null,t), tt, c, ex!=null) ); }
    ;

update_statistics_node returns [UpdateStatisticsStatement ret]
	:   UPDATE STATISTICS t=from_table_name (s=INDEX | s=ALL | s=COLUMNS)? (SET (p=properties))?
		{ret = factory.updateStatistics(factory.namedTable(null, t), s == null ? StatisticsCollectionScope.getDefault() : StatisticsCollectionScope.valueOf(SchemaUtil.normalizeIdentifier(s.getText())), p);}
	;

execute_upgrade_node returns [ExecuteUpgradeStatement ret]
	:   EXECUTE UPGRADE
		{ret = factory.executeUpgrade();}
	;

prop_name returns [String ret]
    :   p=identifier {$ret = SchemaUtil.normalizeIdentifier(p); }
    ;
    
properties returns [Map<String,Object> ret]
@init{ret = new HashMap<String,Object>(); }
    :  k=prop_name EQ v=prop_value {$ret.put(k,v);}  (COMMA k=prop_name EQ v=prop_value {$ret.put(k,v);} )*
    ;

column_defs returns [List<ColumnDef> ret]
@init{ret = new ArrayList<ColumnDef>(); }
    :  v = column_def {$ret.add(v);}  (COMMA v = column_def {$ret.add(v);} )*
;

initializiation_list returns [Map<String, Integer> ret]
@init{ret = new HashMap<String,Integer>(); }
    :   k=STRING_LITERAL EQ v=NUMBER {$ret.put(k.getText(), Integer.parseInt( v.getText() ));}
        (COMMA k=STRING_LITERAL EQ v=NUMBER {$ret.put(k.getText(), Integer.parseInt( v.getText() ));} )*
    ;

indexes returns [List<NamedNode> ret]
@init{ret = new ArrayList<NamedNode>(); }
    :  v = index_name {$ret.add(v);}  (COMMA v = index_name {$ret.add(v);} )*
;

column_def returns [ColumnDef ret]
    :   c=column_name dt=identifier (LPAREN l=NUMBER (COMMA s=NUMBER)? RPAREN)? ar=ARRAY? (lsq=LSQUARE (a=NUMBER)? RSQUARE)? (nn=NOT? n=NULL)? (DEFAULT df=expression)? ((pk=PRIMARY KEY (order=ASC|order=DESC)? rr=ROW_TIMESTAMP?)|(ENCODED_QUALIFIER eq=NUMBER))?
        { $ret = factory.columnDef(
            c,
            dt,
            ar != null || lsq != null,
            a == null ? null :  Integer.parseInt( a.getText() ),
            nn!=null ? Boolean.FALSE : n!=null ? Boolean.TRUE : null,
            l == null ? null : Integer.parseInt( l.getText() ),
            s == null ? null : Integer.parseInt( s.getText() ),
            pk != null, 
            order == null ? SortOrder.getDefault() : SortOrder.fromDDLValue(order.getText()),
            df == null ? null : df.toString(),
            eq == null ? null : Integer.parseInt( eq.getText() ),
            rr != null); }
    ;

dyn_column_defs returns [List<ColumnDef> ret]
@init{ret = new ArrayList<ColumnDef>(); }
    :  v = dyn_column_def {$ret.add(v);}  (COMMA v = dyn_column_def {$ret.add(v);} )*
;

dyn_column_def returns [ColumnDef ret]
    :   c=column_name dt=identifier (LPAREN l=NUMBER (COMMA s=NUMBER)? RPAREN)? ar=ARRAY? (lsq=LSQUARE (a=NUMBER)? RSQUARE)?
        {$ret = factory.columnDef(c, dt, ar != null || lsq != null, a == null ? null :  Integer.parseInt( a.getText() ), Boolean.TRUE,
            l == null ? null : Integer.parseInt( l.getText() ),
            s == null ? null : Integer.parseInt( s.getText() ),
            false, 
            SortOrder.getDefault(),
            false); }
    ;

dyn_column_name_or_def returns [ColumnDef ret]
    :   c=column_name (dt=identifier (LPAREN l=NUMBER (COMMA s=NUMBER)? RPAREN)? ar=ARRAY? (lsq=LSQUARE (a=NUMBER)? RSQUARE)? )? 
        {$ret = factory.columnDef(c, dt, ar != null || lsq != null, a == null ? null :  Integer.parseInt( a.getText() ), Boolean.TRUE,
            l == null ? null : Integer.parseInt( l.getText() ),
            s == null ? null : Integer.parseInt( s.getText() ),
            false, 
            SortOrder.getDefault(),
            false); }
    ;

subquery_expression returns [ParseNode ret]
    :  s=select_node {$ret = factory.subquery(s, false);}
    ;
    
single_select returns [SelectStatement ret]
@init{ contextStack.push(new ParseContext()); }
    :   SELECT (h=hintClause)? 
        (d=DISTINCT | ALL)? sel=select_list
        (FROM from=parseFrom)?
        (WHERE where=expression)?
        (GROUP BY group=group_by)?
        (HAVING having=expression)?
        { ParseContext context = contextStack.peek(); $ret = factory.select(from, h, d!=null, sel, where, group, having, null, null,null, getBindCount(), context.isAggregate(), context.hasSequences(), null, new HashMap<String,UDFParseNode>(udfParseNodes)); }
    ;
finally{ contextStack.pop(); }

unioned_selects returns [List<SelectStatement> ret]
@init{ret = new ArrayList<SelectStatement>();}
    :   s=single_select {ret.add(s);} (UNION ALL s=single_select {ret.add(s);})*
    ;
    
// Parse a full select expression structure.
select_node returns [SelectStatement ret]
@init{ contextStack.push(new ParseContext()); }
    :   u=unioned_selects
        (ORDER BY order=order_by)?
        (LIMIT l=limit)?
        (OFFSET o=offset)?
        (FETCH (FIRST | NEXT) (l=limit)? (ROW | ROWS) ONLY)?
        { ParseContext context = contextStack.peek(); $ret = factory.select(u, order, l, o, getBindCount(), context.isAggregate()); }
    ;
finally{ contextStack.pop(); }

// Parse a full upsert expression structure.
upsert_node returns [UpsertStatement ret]
    :   UPSERT (hint=hintClause)? INTO t=from_table_name
        (LPAREN p=upsert_column_refs RPAREN)?
        ((VALUES LPAREN v=one_or_more_expressions RPAREN ( ON DUPLICATE KEY ( ig=IGNORE |
         ( upd=UPDATE pairs=update_column_pairs ) | ( updo=UPDATE_ONLY upopairs=update_column_pairs ) ) )? )
          | s=select_node)
        {ret = factory.upsert(
            factory.namedTable(null,t,p == null ? null : p.getFirst()), 
            hint, p == null ? null : p.getSecond(), 
            v, s, getBindCount(), 
            new HashMap<String, UDFParseNode>(udfParseNodes),
            ig != null ? Collections.<Pair<ColumnName,ParseNode>>emptyList() : pairs != null ? pairs
             : upopairs != null ? upopairs : null,
            ig != null ? UpsertStatement.OnDuplicateKeyType.IGNORE :
            upd != null ? UpsertStatement.OnDuplicateKeyType.UPDATE :
            updo != null ? UpsertStatement.OnDuplicateKeyType.UPDATE_ONLY
             : UpsertStatement.OnDuplicateKeyType.NONE); }
    ;
  
update_column_pairs returns [ List<Pair<ColumnName,ParseNode>> ret]
@init{ret = new ArrayList<Pair<ColumnName,ParseNode>>(); }
    :  p=update_column_pair { ret.add(p); }
       (COMMA p=update_column_pair { ret.add(p); } )*
;

update_column_pair returns [ Pair<ColumnName,ParseNode> ret ]
    :  c=column_name EQ e=expression { $ret = new Pair<ColumnName,ParseNode>(c,e); }
;

  
upsert_column_refs returns [Pair<List<ColumnDef>,List<ColumnName>> ret]
@init{ret = new Pair<List<ColumnDef>,List<ColumnName>>(new ArrayList<ColumnDef>(), new ArrayList<ColumnName>()); }
    :  d=dyn_column_name_or_def { if (d.getDataType()!=null) { $ret.getFirst().add(d); } $ret.getSecond().add(d.getColumnDefName()); } 
       (COMMA d=dyn_column_name_or_def { if (d.getDataType()!=null) { $ret.getFirst().add(d); } $ret.getSecond().add(d.getColumnDefName()); } )*
;
	

// Parse a full declare cursor expression structure.
declare_cursor_node returns [DeclareCursorStatement ret]
    :    DECLARE c=cursor_name CURSOR FOR s=select_node
        {ret = factory.declareCursor(c, s); }
    ;

cursor_open_node returns [OpenStatement ret]
    :    OPEN c=cursor_name {ret = factory.open(c);}
    ;
 
cursor_close_node returns [CloseStatement ret]
    :    CLOSE c=cursor_name {ret = factory.close(c);}
    ;

cursor_fetch_node returns [FetchStatement ret]
    :    FETCH NEXT (a=NUMBER)? (ROW|ROWS)? FROM c=cursor_name {ret = factory.fetch(c,true, a == null ? 1 :  Integer.parseInt( a.getText() )); }
    ;

// Parse a full delete expression structure.
delete_node returns [DeleteStatement ret]
    :   DELETE (hint=hintClause)? FROM t=from_table_name
        (WHERE v=expression)?
        (ORDER BY order=order_by)?
        (LIMIT l=limit)?
        {ret = factory.delete(factory.namedTable(null,t), hint, v, order, l, getBindCount(), new HashMap<String, UDFParseNode>(udfParseNodes)); }
    ;

limit returns [LimitNode ret]
    : b=bind_expression { $ret = factory.limit(b); }
    | l=int_or_long_literal { $ret = factory.limit(l); }
    ;
    
offset returns [OffsetNode ret]
	: b=bind_expression (ROW | ROWS)? {  try { $ret = factory.offset(b); } catch (SQLException e) { throw new RuntimeException(e); } }
    | l=int_or_long_literal (ROW | ROWS)? { try { $ret = factory.offset(l); } catch (SQLException e) { throw new RuntimeException(e); } }
    | LPAREN lhs=one_or_more_expressions RPAREN EQ LPAREN rhs=one_or_more_expressions RPAREN { try { $ret = factory.offset(factory.comparison(CompareOperator.EQUAL,factory.rowValueConstructor(lhs),factory.rowValueConstructor(rhs)));  } catch (SQLException e) { throw new RuntimeException(e); } }
    ;

sampling_rate returns [LiteralParseNode ret]
    : l=literal { $ret = l; }
    ;

tableSampleNode returns [LiteralParseNode ret]
    : l=literal { $ret = l; }
    ;

hintClause returns [HintNode ret]
    :  c=ML_HINT { $ret = factory.hint(c.getText()); }
    ;

// Parse the column/expression select list part of a select.
select_list returns [List<AliasedNode> ret]
@init{ret = new ArrayList<AliasedNode>();}
    :   n=selectable {ret.add(n);} (COMMA n=selectable {ret.add(n);})*
    |	ASTERISK { $ret = Collections.<AliasedNode>singletonList(factory.aliasedNode(null, factory.wildcard()));} // i.e. the '*' in 'select * from'    
    ;

// Parse either a select field or a sub select.
selectable returns [AliasedNode ret]
    :   field=expression (a=parseAlias)? { $ret = factory.aliasedNode(a, field); }
    | 	familyName=identifier DOT ASTERISK { $ret = factory.aliasedNode(null, factory.family(familyName));} // i.e. the 'cf.*' in 'select cf.* from' cf being column family of an hbase table    
    |   s=identifier DOT t=identifier DOT ASTERISK { $ret = factory.aliasedNode(null, factory.tableWildcard(factory.table(s, t))); }
    ;


// Parse a group by statement
group_by returns [List<ParseNode> ret]
@init{ret = new ArrayList<ParseNode>();}
    :   expr=expression { ret.add(expr); }
        (COMMA expr = expression {ret.add(expr); })*
    ;

// Parse an order by statement
order_by returns [List<OrderByNode> ret]
@init{ret = new ArrayList<OrderByNode>();}
    :   field=parseOrderByField { ret.add(field); }
        (COMMA field = parseOrderByField {ret.add(field); })*
    ;

//parse the individual field for an order by clause
parseOrderByField returns [OrderByNode ret]
@init{boolean isAscending = true; boolean nullsLast = false;}
    :   (expr = expression)
        (ASC {isAscending = true;} | DESC {isAscending = false;})?
        (NULLS (FIRST {nullsLast = false;} | LAST {nullsLast = true;}))?
        { $ret = factory.orderBy(expr, nullsLast, isAscending); }
    ;

parseFrom returns [TableNode ret]
    :   t=table_list {$ret = t;}
    ;
    
table_list returns [TableNode ret]
    :   t=table_ref {$ret = t;} (COMMA s=table_ref { $ret = factory.join(JoinTableNode.JoinType.Inner, ret, s, null, false); })*
    ;

table_ref returns [TableNode ret]
	:	l=table_factor { $ret = l; } (j=join_type JOIN r=table_factor ON e=expression { $ret = factory.join(j, ret, r, e, false); })*
	;

table_factor returns [TableNode ret]
    :   LPAREN t=table_list RPAREN { $ret = t; }
    |   n=bind_name ((AS)? alias=identifier)? { $ret = factory.bindTable(alias, factory.table(null,n)); } // TODO: review
    |   f=from_table_name ((AS)? alias=identifier)? (LPAREN cdefs=dyn_column_defs RPAREN)? (TABLESAMPLE LPAREN tableSample=tableSampleNode RPAREN)? { $ret = factory.namedTable(alias,f,cdefs, tableSample);}
    |   LPAREN s=select_node RPAREN ((AS)? alias=identifier)? { $ret = factory.derivedTable(alias, s); }
    ;

join_type returns [JoinTableNode.JoinType ret]
    :   INNER?   { $ret = JoinTableNode.JoinType.Inner; }
    |   LEFT OUTER?   { $ret = JoinTableNode.JoinType.Left; }
    |   RIGHT OUTER?  { $ret = JoinTableNode.JoinType.Right; }
    |   FULL  OUTER?  { $ret = JoinTableNode.JoinType.Full; }
    ;
    
parseAlias returns [String ret]
    :   AS? alias=parseNoReserved { $ret = alias; }
    ;

// Parse a expression, such as used in a where clause - either a basic one, or an OR of (Single or AND) expressions
expression returns [ParseNode ret]
    :   e=or_expression { $ret = e; }
    ;

// A set of OR'd simple expressions
or_expression returns [ParseNode ret]
@init{List<ParseNode> l = new ArrayList<ParseNode>(4); }
    :   i=and_expression {l.add(i);} (OR i=and_expression {l.add(i);})* { $ret = l.size() == 1 ? l.get(0) : factory.or(l); }
    ;

// A set of AND'd simple expressions
and_expression returns [ParseNode ret]
@init{List<ParseNode> l = new ArrayList<ParseNode>(4); }
    :   i=not_expression {l.add(i);} (AND i=not_expression {l.add(i);})* { $ret = l.size() == 1 ? l.get(0) : factory.and(l); }
    ;

// NOT or parenthesis 
not_expression returns [ParseNode ret]
    :   (NOT? boolean_expression ) => n=NOT? e=boolean_expression { $ret = n == null ? e : factory.not(e); }
    |   n=NOT? LPAREN e=expression RPAREN { $ret = n == null ? e : factory.not(e); }
    ;

comparison_op returns [CompareOperator ret]
	: EQ { $ret = CompareOperator.EQUAL; }
	| LT { $ret = CompareOperator.LESS; }
	| GT { $ret = CompareOperator.GREATER; }
	| LT EQ { $ret = CompareOperator.LESS_OR_EQUAL; }
	| GT EQ { $ret = CompareOperator.GREATER_OR_EQUAL; }
	| (NOEQ1 | NOEQ2) { $ret = CompareOperator.NOT_EQUAL; }
	;
	
boolean_expression returns [ParseNode ret]
    :   l=value_expression ((op=comparison_op (r=value_expression | (LPAREN r=subquery_expression RPAREN) | ((all=ALL | any=ANY) LPAREN r=value_expression RPAREN)  | ((all=ALL | any=ANY) LPAREN r=subquery_expression RPAREN)) {$ret = all != null ? factory.wrapInAll(op, l, r) : any != null ? factory.wrapInAny(op, l, r) : factory.comparison(op,l,r); } )
                  |  (IS n=NOT? NULL {$ret = factory.isNull(l,n!=null); } )
                  |  ( n=NOT? ((LIKE r=value_expression {$ret = factory.like(l,r,n!=null,LikeType.CASE_SENSITIVE); } )
                      |        (ILIKE r=value_expression {$ret = factory.like(l,r,n!=null,LikeType.CASE_INSENSITIVE); } )
                      |        (BETWEEN r1=value_expression AND r2=value_expression {$ret = factory.between(l,r1,r2,n!=null); } )
                      |        ((IN ((r=bind_expression {$ret = factory.inList(Arrays.asList(l,r),n!=null);} )
                                | (LPAREN r=subquery_expression RPAREN {$ret = factory.in(l,r,n!=null,false);} )
                                | (LPAREN v=one_or_more_expressions RPAREN {List<ParseNode> il = new ArrayList<ParseNode>(v.size() + 1); il.add(l); il.addAll(v); $ret = factory.inList(il,n!=null);})
                                )))
                      ))
                   |  { $ret = l; } )
    |   EXISTS LPAREN s=subquery_expression RPAREN {$ret = factory.exists(s,false);}
    ;

bind_expression  returns [BindParseNode ret]
    :   b=bind_name { $ret = factory.bind(b); }
    ;
    
value_expression returns [ParseNode ret]
    :   i=add_expression { $ret = i; }
    ;

add_expression returns [ParseNode ret]
@init{List<ParseNode> l = new ArrayList<ParseNode>(4); }
    :   i=subtract_expression {l.add(i);} (PLUS i=subtract_expression {l.add(i);})* { $ret = l.size() == 1 ? l.get(0) : factory.add(l); }
    ;

subtract_expression returns [ParseNode ret]
@init{List<ParseNode> l = new ArrayList<ParseNode>(4); }
    :   i=concat_expression {l.add(i);} (MINUS i=concat_expression {l.add(i);})* { $ret = l.size() == 1 ? l.get(0) : factory.subtract(l); }
    ;

concat_expression returns [ParseNode ret]
@init{List<ParseNode> l = new ArrayList<ParseNode>(4); }
    :   i=multiply_divide_modulo_expression {l.add(i);} (CONCAT i=multiply_divide_modulo_expression {l.add(i);})* { $ret = l.size() == 1 ? l.get(0) : factory.concat(l); }
    ;

multiply_divide_modulo_expression returns [ParseNode ret]
@init{ParseNode lhs = null; List<ParseNode> l;}
    :   i=negate_expression {lhs = i;} 
        (op=(ASTERISK | DIVIDE | PERCENT) rhs=negate_expression {
            l = Arrays.asList(lhs, rhs); 
            // determine the expression type based on the operator found
            lhs = op.getType() == ASTERISK ? factory.multiply(l)
                : op.getType() == DIVIDE   ? factory.divide(l)
                : factory.modulus(l);
            }
        )*
        { $ret = lhs; }
    ;

use_schema_node returns [UseSchemaStatement ret]
	:   USE (DEFAULT | s=identifier)
        {ret = factory.useSchema(s); }
    ;

negate_expression returns [ParseNode ret]
    :   m=MINUS? e=array_expression { $ret = m==null ? e : factory.negate(e); }
    ;

// The lowest level function, which includes literals, binds, but also parenthesized expressions, functions, and case statements.
array_expression returns [ParseNode ret]
    :   e=term (LSQUARE s=value_expression RSQUARE)?  { if (s == null) { $ret = e; } else { $ret = factory.arrayElemRef(Arrays.<ParseNode>asList(e,s)); } } 
	;
	    
term returns [ParseNode ret]
    :   e=literal_or_bind { $ret = e; }
    |   field=identifier { $ret = factory.column(null,field,field); }
    |   ex=ARRAY LSQUARE v=one_or_more_expressions RSQUARE {$ret = factory.upsertStmtArrayNode(v);}
    |   tableName=table_name DOT field=identifier { $ret = factory.column(tableName, field, field); }
    |   field=identifier LPAREN l=zero_or_more_expressions RPAREN wg=(WITHIN GROUP LPAREN ORDER BY l2=one_or_more_expressions (a=ASC | DESC) RPAREN)?
        {
            FunctionParseNode f = wg==null ? factory.function(field, l) : factory.function(field,l,l2,a!=null);
            if (!contextStack.isEmpty()) {
            	contextStack.peek().setAggregate(f.isAggregate());
            }
            if(f instanceof UDFParseNode) udfParseNodes.put(f.getName(),(UDFParseNode)f);
            $ret = f;
        } 
    |   field=identifier LPAREN t=ASTERISK RPAREN 
        {
            if (!isCountFunction(field)) {
                throwRecognitionException(t);
            }
            FunctionParseNode f = factory.function(field, LiteralParseNode.STAR);
            if (!contextStack.isEmpty()) {
            	contextStack.peek().setAggregate(f.isAggregate());
            }
            if(f instanceof UDFParseNode) udfParseNodes.put(f.getName(),(UDFParseNode)f);
            $ret = f;
        } 
    |   field=identifier LPAREN t=DISTINCT l=zero_or_more_expressions RPAREN 
        {
            FunctionParseNode f = factory.functionDistinct(field, l);
            if (!contextStack.isEmpty()) {
            	contextStack.peek().setAggregate(f.isAggregate());
            }
            if(f instanceof UDFParseNode) udfParseNodes.put(f.getName(),(UDFParseNode)f);
            $ret = f;
        }
    |   e=case_statement { $ret = e; }
    |   LPAREN l=one_or_more_expressions RPAREN 
    	{ 
    		if(l.size() == 1) {
    			$ret = l.get(0);
    		}	
    		else {
    			$ret = factory.rowValueConstructor(l);
    		}	 
    	}
    |   CAST LPAREN e=expression AS dt=identifier (LPAREN length=NUMBER (COMMA scale=NUMBER)? RPAREN)? ar=(ARRAY | (LSQUARE RSQUARE))? RPAREN
        { $ret = factory.cast(e, dt,
                     length == null ? null : Integer.parseInt(length.getText()),
                     scale == null ? null : Integer.parseInt(scale.getText()),
                     ar!=null);
        }
    |   (n=NEXT | CURRENT) VALUE FOR s=from_table_name 
        { contextStack.peek().hasSequences(true);
          $ret = n==null ? factory.currentValueFor(s) : factory.nextValueFor(s, null); }    
    |   (n=NEXT) lorb=literal_or_bind VALUES FOR s=from_table_name 
        { contextStack.peek().hasSequences(true);
          $ret = factory.nextValueFor(s, lorb); }    
    ;

one_or_more_expressions returns [List<ParseNode> ret]
@init{ret = new ArrayList<ParseNode>(); }
    :  e = expression {$ret.add(e);}  (COMMA e = expression {$ret.add(e);} )*
;

one_or_more_jarpaths returns [List<LiteralParseNode> ret]
@init{ret = new ArrayList<LiteralParseNode>(); }
    :  jarPath = jar_path {$ret.add(jarPath);}  (COMMA jarPath = jar_path {$ret.add(jarPath);} )*
	;

zero_or_more_expressions returns [List<ParseNode> ret]
@init{ret = new ArrayList<ParseNode>(); }
    :  (v = expression {$ret.add(v);})?  (COMMA v = expression {$ret.add(v);} )*
;

zero_or_more_data_types returns [List<FunctionArgument> ret]
@init{ret = new ArrayList<FunctionArgument>(); }
    : (fa = function_argument {$ret.add(fa);})? (COMMA fa = function_argument {$ret.add(fa);})* 
	;

function_argument returns [FunctionArgument ret]
	: (dt = identifier (LPAREN l=NUMBER (COMMA s=NUMBER)? RPAREN)? ar=ARRAY? (lsq=LSQUARE (a=NUMBER)? RSQUARE)? (c = CONSTANT)? (DEFAULTVALUE EQ dv = expression)? (MINVALUE EQ minv = expression)?  (MAXVALUE EQ maxv = expression)? 
	{ $ret = new FunctionArgument(dt,  ar != null || lsq != null, c!=null, 
    dv == null ? null : LiteralExpression.newConstant(((LiteralParseNode)dv).getValue()), 
    minv == null ? null : LiteralExpression.newConstant(((LiteralParseNode)minv).getValue()), 
    maxv == null ? null : LiteralExpression.newConstant(((LiteralParseNode)maxv).getValue()));})
	;

value_expression_list returns [List<ParseNode> ret]
@init{ret = new ArrayList<ParseNode>(); }
    :  LPAREN e = value_expression {$ret.add(e);}  (COMMA e = value_expression {$ret.add(e);} )* RPAREN
;

index_name returns [NamedNode ret]
    :   name=identifier {$ret = factory.indexName(name); }
    ;

cursor_name returns [CursorName ret]
    :   name=identifier {$ret = factory.cursorName(name);}
    ;

// TODO: figure out how not repeat this two times
table_name returns [TableName ret]
    :   t=table_identifier {$ret = factory.table(null, t); }
    |   s=table_identifier DOT t=table_identifier {$ret = factory.table(s, t); }
    ;

// TODO: figure out how not repeat this two times
from_table_name returns [TableName ret]
    :   t=table_identifier {$ret = factory.table(null, t); }
    |   s=table_identifier DOT t=table_identifier {$ret = factory.table(s, t); }
    ;

table_identifier returns [String ret]
    :   c=identifier {
           if (c.contains(QueryConstants.NAMESPACE_SEPARATOR) ) { throw new RuntimeException("Table or schema name cannot contain colon"); }
           $ret = c;
    }
    ;
    
// The lowest level function, which includes literals, binds, but also parenthesized expressions, functions, and case statements.
literal_or_bind returns [ParseNode ret]
    :   e=literal { $ret = e; }
    |   b=bind_name { $ret = factory.bind(b); }    
    ;

// Get a string, integer, double, date, boolean, or NULL value.
literal returns [LiteralParseNode ret]
    :
    h=hex_literal { ret = h; }
    |   b=bin_literal { ret = b; }
    |   s=STRING_LITERAL {
            ret = factory.literal(s.getText()); 
        }
    |   n=NUMBER {
            ret = factory.wholeNumber(n.getText());
        }
    |   d=DECIMAL  {
            ret = factory.realNumber(d.getText());
        }
    |   dbl=DOUBLE  {
            ret = factory.literal(Double.valueOf(dbl.getText()));
        }
    |   NULL {ret = factory.literal(null);}
    |   TRUE {ret = factory.literal(Boolean.TRUE);} 
    |   FALSE {ret = factory.literal(Boolean.FALSE);}
    |   dt=identifier t=STRING_LITERAL { 
            try {
                ret = factory.literal(t.getText(), dt);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    ;

int_or_long_literal returns [LiteralParseNode ret]
    :   n=NUMBER {
            ret = factory.intOrLong(n.getText());
        }
    ;

hex_literal returns [LiteralParseNode ret]
@init {StringBuilder sb = new StringBuilder();}
    :
    (
    h=HEX_LITERAL { sb.append(h.getText()); }
    (s=STRING_LITERAL { sb.append(factory.stringToHexLiteral(s.getText())); } )*
    )
    { ret = factory.hexLiteral(sb.toString()); }
    ;

bin_literal returns [LiteralParseNode ret]
@init {StringBuilder sb = new StringBuilder();}
    :
    (
    b=BIN_LITERAL { sb.append(b.getText()); }
    (s=STRING_LITERAL { sb.append(factory.stringToBinLiteral(s.getText())); } )*
    )
    { ret = factory.binLiteral(sb.toString()); }
    ;

// Bind names are a colon followed by 1+ letter/digits/underscores, or '?' (unclear how Oracle acutally deals with this, but we'll just treat it as a special bind)
bind_name returns [String ret]
    :   n=BIND_NAME { String bind = n.getText().substring(1); updateBind(bind); $ret = bind; } 
    |   QUESTION { $ret = nextBind(); } // TODO: only support this?
    ;

// Parse a field, includes line and column information.
identifier returns [String ret]
    :   c=parseNoReserved { $ret = c; }
    ;

parseNoReserved returns [String ret]
    :   n=NAME { $ret = n.getText(); }
    ;

case_statement returns [ParseNode ret]
@init{List<ParseNode> w = new ArrayList<ParseNode>(4);}
    : CASE e1=expression (WHEN e2=expression THEN t=expression {w.add(t);w.add(factory.equal(e1,e2));})+ (ELSE el=expression {w.add(el);})? END {$ret = factory.caseWhen(w);}
    | CASE (WHEN c=expression THEN t=expression {w.add(t);w.add(c);})+ (ELSE el=expression {w.add(el);})? END {$ret = factory.caseWhen(w);}
    ;

// --------------------------------------
// The Lexer

HINT_START: '/*+' ;
COMMENT_START: '/*';
COMMENT_AND_HINT_END: '*/' ;
SL_COMMENT1: '//';
SL_COMMENT2: '--';

// Bind names start with a colon and followed by 1 or more letter/digit/underscores
BIND_NAME
    : COLON (DIGIT)+
    ;

HEX_LITERAL
@init{ StringBuilder sb = new StringBuilder();}
    :
    X { $type = NAME;}
    (
    (FIELDCHAR) => FIELDCHAR+
    | ('\'') => '\'' ' '* ( d=HEX_DIGIT { sb.append(d.getText()); } ' '* )* '\'' { $type=HEX_LITERAL; }
    )?
    { if ($type == HEX_LITERAL) { setText(sb.toString()); } }
    ;

BIN_LITERAL
@init{ StringBuilder sb = new StringBuilder();}
    :
    B { $type = NAME;}
    (
    (FIELDCHAR) => FIELDCHAR+
    | ('\'') =>  '\'' ' '* ( d=BIN_DIGIT { sb.append(d.getText()); } ' '* )* '\'' { $type=BIN_LITERAL; }
    )?
    { if ($type == BIN_LITERAL) { setText(sb.toString()); } }
    ;

NAME
    :    LETTER (FIELDCHAR)*
    |    '\"' (DBL_QUOTE_CHAR)* '\"'
    ;

// An integer number, positive or negative
NUMBER
    :   POSINTEGER
    ;

DECIMAL
	:	POSINTEGER? '.' POSINTEGER
	;
	
DOUBLE
    :   '.' POSINTEGER Exponent
    |   POSINTEGER '.' Exponent
    |   POSINTEGER ('.' (POSINTEGER (Exponent)?)? | Exponent)
    ;

Exponent
    :    ('e' | 'E') ( PLUS | MINUS )? POSINTEGER
    ;

DOUBLE_QUOTE
    :   '"'
    ;

EQ
    :   '='
    ;

LT
    :   '<'
    ;

GT
    :   '>'
    ;

DOUBLE_EQ
    :   '=''='
    ;

NOEQ1
    :   '!''='
    ;

NOEQ2
    :   '<''>'
    ;

CONCAT
    :   '|''|'
    ;

COMMA
    :   ','
    ;

LPAREN
    :   '('
    ;

RPAREN
    :   ')'
    ;

SEMICOLON
    :   ';'
    ;

COLON
    :   ':'
    ;

QUESTION
    :   '?'
    ;

LSQUARE
    :   '['
    ;

RSQUARE
    :   ']'
    ;

LCURLY
    :   '{'
    ;

RCURLY
    :   '}'
    ;

AT
    :   '@'
    ;

TILDE
    :   '~'
    ;

PLUS
    :   '+'
    ;

MINUS
    :   '-'
    ;

ASTERISK
    :   '*'
    ;

DIVIDE
    :   '/'
    ;
    
PERCENT
    :   '%'
    ;

OUTER_JOIN
    : '(' '+' ')'
    ;
// A FieldCharacter is a letter, digit, underscore, or a certain unicode section.
fragment
FIELDCHAR
    :    LETTER
    |    DIGIT
    |    '_'
    |    '\u0080'..'\u2001'
    |    '\u2003'..'\ufffe'
    ;

// A Letter is a lower or upper case ascii character.
fragment
LETTER
    :    'a'..'z'
    |    'A'..'Z'
    ;

fragment
POSINTEGER
    :   DIGIT+
    ;

fragment
DIGIT
    :    '0'..'9'
    ;

fragment
HEX_DIGIT
    :   ('0'..'9' | 'a'..'f' | 'A'..'F')
    ;

fragment
BIN_DIGIT
    :   ('0' | '1')
    ;

// string literals
STRING_LITERAL
@init{ StringBuilder sb = new StringBuilder(); }
    :   '\''
    ( t=CHAR { sb.append(t.getText()); } 
    | t=CHAR_ESC { sb.append(getText()); }
    )* '\'' { setText(sb.toString()); }
    ;

fragment
CHAR
    :   ( ~('\'' | '\\') )
    ;

fragment
DBL_QUOTE_CHAR
    :   ( ~('\"') )+
    ;

fragment
X
    : ( 'X' | 'x' )
    ;

fragment
B
    : ( 'B' | 'b' )
    ;

// escape sequence inside a string literal
fragment
CHAR_ESC
    :   '\\'
        ( 'n'   { setText("\n"); }
        | 'r'   { setText("\r"); }
        | 't'   { setText("\t"); }
        | 'b'   { setText("\b"); }
        | 'f'   { setText("\f"); }
        | '\"'  { setText("\""); }
        | '\''  { setText("\'"); }
        | '\\'  { setText("\\"); }
        | '_'   { setText("\\_"); }
        | '%'   { setText("\\\%"); }
        |       { setText("\\"); }
        )
    |   '\'\''  { setText("\'"); }
    ;

// whitespace (skip)
WS
    :   ( ' ' | '\t' | '\u2002' ) { $channel=HIDDEN; }
    ;

EOL
    :  ('\r' | '\n')
    { skip(); }
    ;

// Keep everything in comment in a case sensitive manner
ML_HINT
@init{ StringBuilder sb = new StringBuilder(); }
    : h=HINT_START ( options {greedy=false;} : t=.)*  { sb.append($text); }  COMMENT_AND_HINT_END
    { setText(sb.substring(h.getText().length())); } // Get rid of the HINT_START text
    ;

ML_COMMENT
    : COMMENT_START (~PLUS) ( options {greedy=false;} : . )* COMMENT_AND_HINT_END
    { skip(); }
    ;

SL_COMMENT
    : (SL_COMMENT1 | SL_COMMENT2) ( options {greedy=false;} : . )* EOL
    { skip(); }
    ;

DOT
    : '.'
    ;

OTHER      
    : . { if (true) // to prevent compile error
              throw new RuntimeException("Unexpected char: '" + $text + "'"); } 
    ;


