package com.irvil.nntextclassifier.model;

import com.irvil.nntextclassifier.dao.DAOFactory;

public class Module extends Catalog {
  public Module(int id, String value) {
    super(id, value, DAOFactory.moduleDAO("jdbc", "SQLite"));
  }
}