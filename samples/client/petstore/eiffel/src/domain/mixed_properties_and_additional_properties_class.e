note
 description:"[
		Swagger Petstore
 		This spec is mainly for testing Petstore server and contains fake endpoints, models. Please do not use this for any other purpose. Special characters: \" \\
  		OpenAPI spec version: 1.0.0
 	    Contact: apiteam@swagger.io

  	NOTE: This class is auto generated by the swagger code generator program.

 		 Do not edit the class manually.
 	]"
	date: "$Date$"
	revision: "$Revision$"
	EIS:"Eiffel swagger codegen", "src=https://github.com/swagger-api/swagger-codegen.git", "protocol=uri"

class MIXED_PROPERTIES_AND_ADDITIONAL_PROPERTIES_CLASS 

inherit

  ANY
      redefine
          out 
      end


feature --Access

    uuid: detachable UUID 
      
    date_time: detachable DATE_TIME 
      
    map: detachable STRING_TABLE[ANIMAL] 
      

feature -- Change Element  
 
    set_uuid (a_name: like uuid)
        -- Set 'uuid' with 'a_name'.
      do
        uuid := a_name
      ensure
        uuid_set: uuid = a_name		
      end

    set_date_time (a_name: like date_time)
        -- Set 'date_time' with 'a_name'.
      do
        date_time := a_name
      ensure
        date_time_set: date_time = a_name		
      end

    set_map (a_name: like map)
        -- Set 'map' with 'a_name'.
      do
        map := a_name
      ensure
        map_set: map = a_name		
      end


 feature -- Status Report

    out: STRING
          -- <Precursor>
      do
        create Result.make_empty
        Result.append("%Nclass MIXED_PROPERTIES_AND_ADDITIONAL_PROPERTIES_CLASS%N")
        if attached uuid as l_uuid then
          Result.append ("%N")
          Result.append (l_uuid.out)
          Result.append ("%N")    
        end  
        if attached date_time as l_date_time then
          Result.append ("%N")
          Result.append (l_date_time.out)
          Result.append ("%N")    
        end  
        if attached map as l_map then
          across l_map as ic loop
            Result.append ("%N")
            Result.append ("key:")
            Result.append (ic.key.out)
            Result.append (" - ")
            Result.append ("val:")
            Result.append (ic.item.out)
            Result.append ("%N")
          end
        end        
      end
end
