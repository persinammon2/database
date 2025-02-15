package edu.berkeley.cs186.database.query;

import java.util.*;

import edu.berkeley.cs186.database.DatabaseException;
import edu.berkeley.cs186.database.Database;
import edu.berkeley.cs186.database.databox.DataBox;
import edu.berkeley.cs186.database.table.Record;
import edu.berkeley.cs186.database.table.stats.TableStats;


public class GraceHashOperator extends JoinOperator {

  private int numBuffers;

  public GraceHashOperator(QueryOperator leftSource,
                           QueryOperator rightSource,
                           String leftColumnName,
                           String rightColumnName,
                           Database.Transaction transaction) throws QueryPlanException, DatabaseException {
    super(leftSource,
            rightSource,
            leftColumnName,
            rightColumnName,
            transaction,
            JoinType.GRACEHASH);

    this.numBuffers = transaction.getNumMemoryPages();
    this.stats = this.estimateStats();
    this.cost = this.estimateIOCost();
  }

  public Iterator<Record> iterator() throws QueryPlanException, DatabaseException {
    return new GraceHashIterator();
  }

  public int estimateIOCost() throws QueryPlanException {
    /* TODO: Implement me! */
    TableStats right = getRightSource().getStats();
    TableStats left = getLeftSource().getStats();
    return 3*(right.getNumPages() + left.getNumPages());

  }

  /**
   * An implementation of Iterator that provides an iterator interface for this operator.
   */
  private class GraceHashIterator implements Iterator<Record> {
    private Iterator<Record> leftIterator;
    private Iterator<Record> rightIterator;
    private Record rightRecord;
    private Record nextRecord;
    private String[] leftPartitions;
    private String[] rightPartitions;
    private int currentPartition;
    private Map<DataBox, ArrayList<Record>> inMemoryHashTable;
    private int currIndexInList;
    private ArrayList<Record> currList;
    private List<DataBox> rightRecordVals;

    public GraceHashIterator() throws QueryPlanException, DatabaseException {
      this.leftIterator = getLeftSource().iterator();
      this.rightIterator = getRightSource().iterator();
      leftPartitions = new String[numBuffers - 1];
      rightPartitions = new String[numBuffers - 1];
      this.inMemoryHashTable = new HashMap<DataBox, ArrayList<Record>>();
      currIndexInList = 0;
      String leftTableName;
      String rightTableName;
      for (int i = 0; i < numBuffers - 1; i++) {
        leftTableName = "Temp HashJoin Left Partition " + Integer.toString(i);
        rightTableName = "Temp HashJoin Right Partition " + Integer.toString(i);
        GraceHashOperator.this.createTempTable(getLeftSource().getOutputSchema(), leftTableName);
        GraceHashOperator.this.createTempTable(getRightSource().getOutputSchema(), rightTableName);
        leftPartitions[i] = leftTableName;
        rightPartitions[i] = rightTableName;
      }
      List<DataBox> values;
      DataBox val;
      int partition;
      while (this.leftIterator.hasNext()) {
        values = this.leftIterator.next().getValues();
        val = values.get(GraceHashOperator.this.getLeftColumnIndex());
        partition = val.hashCode() % (numBuffers - 1);
        GraceHashOperator.this.addRecord(leftPartitions[partition], values);
      }
      while (this.rightIterator.hasNext()) {
        values = this.rightIterator.next().getValues();
        val = values.get(GraceHashOperator.this.getRightColumnIndex());
        partition = val.hashCode() % (numBuffers - 1);
        GraceHashOperator.this.addRecord(rightPartitions[partition], values);
      }
      this.currentPartition = 0;
      leftIterator = GraceHashOperator.this.getTableIterator(leftPartitions[currentPartition]);
      while (leftIterator.hasNext()) {
        Record currRecord = leftIterator.next();
        values = currRecord.getValues();
        val = values.get(GraceHashOperator.this.getLeftColumnIndex());
        if (this.inMemoryHashTable.containsKey(val)) {
          this.inMemoryHashTable.get(val).add(currRecord);
        } else {
          ArrayList<Record> newList = new ArrayList<Record>();
          newList.add(currRecord);
          this.inMemoryHashTable.put(val, newList);
        }
      }
      this.nextRecord = null;
      this.rightIterator = GraceHashOperator.this.getTableIterator(rightPartitions[currentPartition]);
      if (this.rightIterator.hasNext()) {
        this.rightRecord = this.rightIterator.next();
        this.rightRecordVals = this.rightRecord.getValues();
        this.currList = this.inMemoryHashTable.get(this.rightRecord.getValues().get(GraceHashOperator.this.getRightColumnIndex()));
      }

    }

    /**
     * Checks if there are more record(s) to yield
     *
     * @return true if this iterator has another record to yield, otherwise false
     */
    public boolean hasNext() {
      if (this.nextRecord != null) {
        return true;
      }
      while (true) {
        while (this.currList == null && this.rightIterator.hasNext()) {
          this.rightRecord = this.rightIterator.next();
          this.rightRecordVals = this.rightRecord.getValues();
          this.currIndexInList = 0;
          this.currList = this.inMemoryHashTable.get(this.rightRecord.getValues().get(GraceHashOperator.this.getRightColumnIndex()));
        }
        if (this.currList != null) {
          if (this.currIndexInList < this.currList.size()) {
            List<DataBox> leftValues = new ArrayList<DataBox>(this.currList.get(this.currIndexInList).getValues());
            leftValues.addAll(rightRecordVals);
            this.nextRecord = new Record(leftValues);
            this.currIndexInList++;
            return true;
          } else {
            this.currList = null;
          }
        } else {
          if (this.currentPartition < numBuffers - 2)  {
            this.currentPartition++;
            try {
              this.rightIterator = GraceHashOperator.this.getTableIterator(rightPartitions[currentPartition]);
              this.leftIterator = GraceHashOperator.this.getTableIterator(leftPartitions[currentPartition]);
            } catch (DatabaseException d){
              return false;
            }
            this.inMemoryHashTable = new HashMap<DataBox, ArrayList<Record>>();
            while (leftIterator.hasNext()) {
              Record currRecord = leftIterator.next();
              List<DataBox> values = currRecord.getValues();
              DataBox val = values.get(GraceHashOperator.this.getLeftColumnIndex());
              if (this.inMemoryHashTable.containsKey(val)) {
                this.inMemoryHashTable.get(val).add(currRecord);
              } else {
                ArrayList<Record> newList = new ArrayList<Record>();
                newList.add(currRecord);
                this.inMemoryHashTable.put(val, newList);
              }
            }
            this.currList = null;
          } else {
            return false;
          }
        }
      }
    }

    /**
     * Yields the next record of this iterator.
     *
     * @return the next Record
     * @throws NoSuchElementException if there are no more Records to yield
     */
    public Record next() {
      if (this.hasNext()) {
        Record r = this.nextRecord;
        this.nextRecord = null;
        return r;
      }
      throw new NoSuchElementException();
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
}